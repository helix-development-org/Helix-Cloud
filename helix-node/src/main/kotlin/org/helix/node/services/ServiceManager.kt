package org.helix.node.services

import org.helix.api.bridge.HeartbeatReport
import org.helix.api.execution.ExecutorType
import org.helix.api.service.ServiceInfo
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.tasks.TaskStore
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the full lifecycle of all services on this node.
 *
 * Services are created from tasks, executed through the configured
 * [ServiceExecutor] and tracked until they terminate. Dynamic services lose
 * their workspace after stopping; static services keep it.
 *
 * @property taskStore known task definitions.
 * @property workspacePreparer builds service workspaces.
 * @property executors execution backends by type.
 * @property environmentProvider extra environment variables per service,
 *   for example control URL and token for the bridge.
 * @property clock epoch millis source, injectable for tests.
 * @property registry on-disk mirror of the services map, rewritten on every
 *   lifecycle change so a restarted node can re-adopt surviving services.
 * @property portAllocator finds free service ports; injectable for tests.
 */
class ServiceManager(
    private val taskStore: TaskStore,
    private val workspacePreparer: WorkspacePreparer,
    private val executors: Map<ExecutorType, ServiceExecutor>,
    private val environmentProvider: (ManagedService) -> Map<String, String> = { emptyMap() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val eventSink: (category: String, level: String, message: String) -> Unit = { _, _, _ -> },
    private val registry: ServiceRegistryFile? = null,
    private val portAllocator: PortAllocator = PortAllocator(),
) {
    private val logger = LoggerFactory.getLogger(ServiceManager::class.java)
    private val services = linkedMapOf<String, ManagedService>()
    private val stopListeners = CopyOnWriteArrayList<(ManagedService) -> Unit>()

    /**
     * Registers a listener invoked after a service terminated.
     *
     * @param listener receives the terminated service.
     */
    fun onServiceTerminated(listener: (ManagedService) -> Unit) {
        stopListeners += listener
    }

    /**
     * Lists snapshots of all known services.
     *
     * @return snapshots sorted by service id.
     */
    @Synchronized
    fun services(): List<ServiceInfo> = services.values.map { it.toInfo() }.sortedBy { it.id }

    /**
     * Looks up a service.
     *
     * @param id the service id.
     * @return the managed service or `null`.
     */
    @Synchronized
    fun find(id: String): ManagedService? = services[id]

    /**
     * Lists all managed services with their live state.
     *
     * @return managed services sorted by id.
     */
    @Synchronized
    fun managedServices(): List<ManagedService> = services.values.sortedBy { it.id }

    /**
     * Counts services of a task that still occupy capacity.
     *
     * @param taskName the task name.
     * @return number of non-terminated services.
     */
    @Synchronized
    fun activeCount(taskName: String): Int =
        services.values.count { it.task.name == taskName && it.active() }

    /**
     * Starts a new service of the given task.
     *
     * @param taskName name of the task to instantiate.
     * @return snapshot of the started service.
     * @throws IllegalArgumentException if the task is unknown or its
     *   `maxServiceCount` is reached.
     */
    fun startService(taskName: String): ServiceInfo {
        val task = requireNotNull(taskStore.find(taskName)) { "unknown task: $taskName" }
        val managed = reserveService(task)
        return runCatching { launch(managed) }
            .onFailure { failure ->
                managed.state = ServiceState.FAILED
                logger.error("Failed to start {}", managed.id, failure)
            }
            .getOrThrow()
    }

    /**
     * Reserves an id, port and map slot for a new service of [task].
     *
     * The port probe binds real sockets, so it must not run while holding
     * the manager lock — otherwise every reader (routing, dashboard,
     * heartbeats) would stall behind socket syscalls. The probe therefore
     * runs against a snapshot of the currently used ports, and only the
     * final reservation re-validates the candidate under the lock; if a
     * concurrent start claimed the candidate in between, the probe retries
     * with that port excluded.
     */
    private fun reserveService(task: TaskDefinition): ManagedService {
        val lostCandidates = mutableSetOf<Int>()
        while (true) {
            val usedPorts = synchronized(this) {
                ensureCapacity(task)
                services.values.filter { it.active() }.map { it.port }.toSet()
            }
            val candidate = portAllocator.allocate(task.startPort, usedPorts + lostCandidates)
            val reserved = synchronized(this) {
                ensureCapacity(task)
                if (services.values.any { it.active() && it.port == candidate }) {
                    lostCandidates += candidate
                    null
                } else {
                    val id = allocateId(task)
                    ManagedService(id, task, workspacePreparer.workspaceFor(task, id), candidate)
                        .also { services[it.id] = it }
                }
            }
            if (reserved != null) {
                return reserved
            }
        }
    }

    private fun ensureCapacity(task: TaskDefinition) {
        require(activeCount(task.name) < task.maxServiceCount) {
            "task ${task.name} already runs ${task.maxServiceCount} services"
        }
    }

    /**
     * Requests a graceful stop.
     *
     * @param id the service id.
     * @return `true` if the service was running and the stop was issued.
     */
    fun stopService(id: String): Boolean {
        // Check and STOPPING-transition are atomic under the manager lock:
        // racing a concurrent exit could otherwise stamp STOPPING onto an
        // already-terminated service, leaving a zombie that never settles.
        // The blocking handle.stop() itself runs outside the lock.
        val handle = synchronized(this) {
            val managed = services[id] ?: return false
            val handle = managed.handle
            if (handle == null || !managed.active()) {
                return false
            }
            managed.stopRequested = true
            managed.state = ServiceState.STOPPING
            handle
        }
        logger.info("Stopping {}", id)
        handle.stop()
        persistRegistry()
        return true
    }

    /**
     * Kills a service immediately.
     *
     * @param id the service id.
     * @return `true` if the service was running.
     */
    fun killService(id: String): Boolean {
        val handle = synchronized(this) {
            val managed = services[id] ?: return false
            val handle = managed.handle ?: return false
            if (!managed.active()) {
                return false
            }
            managed.stopRequested = true
            managed.state = ServiceState.STOPPING
            handle
        }
        handle.kill()
        return true
    }

    /**
     * Kills a stuck or unresponsive service on behalf of the heartbeat
     * watchdog: a service stuck in `STARTING` past its start deadline, or a
     * `RUNNING` service that stopped heartbeating.
     *
     * Unlike [killService], the service always settles as `FAILED` once its
     * process exits, so it is picked up by the same crash-cooldown/replace
     * path as an ordinary crash — and it stops being `RUNNING` immediately,
     * so routing excludes it right away.
     *
     * @param id the service id.
     * @param reason human-readable reason, logged and put on the event log.
     * @return `true` if the service was active and the kill was issued.
     */
    fun watchdogFail(id: String, reason: String): Boolean {
        val handle = synchronized(this) {
            val managed = services[id] ?: return false
            val handle = managed.handle ?: return false
            if (!managed.active()) {
                return false
            }
            managed.watchdogKilled = true
            managed.state = ServiceState.STOPPING
            handle
        }
        logger.warn("Watchdog killing {}: {}", id, reason)
        eventSink("service", "warn", "Watchdog killing $id: $reason")
        handle.kill()
        return true
    }

    /**
     * Stops all active services, used on node shutdown.
     */
    fun stopAll() {
        services().filter { it.state != ServiceState.STOPPED && it.state != ServiceState.FAILED }
            .forEach { stopService(it.id) }
    }

    /**
     * Reads the newest log lines of a service; for terminated services the
     * output captured at termination is returned.
     *
     * @param id the service id.
     * @param tail maximum number of lines from the end.
     * @return log lines, or empty for unknown services.
     */
    fun logs(id: String, tail: Int): List<String> = find(id)
        ?.let { managed -> managed.handle?.logs(tail) ?: managed.lastLogs.takeLast(tail) }
        ?: emptyList()

    /**
     * Sends a console command line to a running service.
     *
     * @param id the service id.
     * @param line the command, without a trailing newline.
     * @return `true` if delivered to the service's console.
     */
    fun sendCommand(id: String, line: String): Boolean =
        find(id)?.handle?.sendCommand(line) ?: false

    /**
     * Applies a bridge heartbeat.
     *
     * The first heartbeat moves the service to `RUNNING`; player counts feed
     * the auto-scaler and the empty-since tracking.
     *
     * @param report the received heartbeat.
     * @return `true` if the service is known.
     */
    fun handleHeartbeat(report: HeartbeatReport): Boolean {
        val managed = find(report.serviceId) ?: return false
        val now = clock()
        managed.lastHeartbeatEpochMs = now
        managed.onlinePlayers = report.onlinePlayers
        if (report.maxPlayers > 0) {
            managed.maxPlayers = report.maxPlayers
        }
        managed.tps = report.tps
        managed.memoryUsedMb = report.memoryUsedMb
        managed.memoryMaxMb = report.memoryMaxMb
        managed.cpuPercent = report.cpuPercent
        if (managed.state == ServiceState.STARTING) {
            managed.state = ServiceState.RUNNING
            logger.info("Service {} is now RUNNING", managed.id)
            eventSink("service", "info", "${managed.id} is now running")
            persistRegistry()
        }
        managed.emptySinceEpochMs = when {
            report.onlinePlayers > 0 -> null
            else -> managed.emptySinceEpochMs ?: now
        }
        return true
    }

    private fun launch(managed: ManagedService): ServiceInfo {
        val task = managed.task
        workspacePreparer.prepare(task, managed.id, managed.port)
        val executor = requireNotNull(executors[task.executor]) {
            "no executor registered for ${task.executor}"
        }
        val environment = baseEnvironment(managed) + environmentProvider(managed)
        // Remember the token exactly as injected into the process: it is
        // persisted with the registry so a successor node can restore it
        // when it re-adopts this service after a backend restart.
        managed.controlToken = environment[CONTROL_TOKEN_ENV]
        val spec = ServiceStartSpec(
            serviceId = managed.id,
            task = task,
            workspace = managed.workspace,
            port = managed.port,
            environmentVariables = environment,
        )
        managed.state = ServiceState.STARTING
        managed.startedAtEpochMs = clock()
        managed.stopRequested = false
        managed.emptySinceEpochMs = clock()
        val handle = executor.start(spec)
        managed.handle = handle
        handle.onExit { exitCode -> onExit(managed, exitCode) }
        logger.info("Started {} on port {} via {}", managed.id, managed.port, task.executor)
        eventSink("service", "info", "Started ${managed.id} on port ${managed.port} via ${task.executor}")
        persistRegistry()
        return managed.toInfo()
    }

    /**
     * Re-adopts a service that survived a node restart headless.
     *
     * The service is registered as `RUNNING` with the given handle; the next
     * bridge heartbeat refreshes its live stats. Ids and ports of adopted
     * services are respected by future allocations.
     *
     * @param task the owning task definition.
     * @param entry the persisted registry entry.
     * @param handle re-attached control handle.
     * @return the adopted service.
     */
    @Synchronized
    fun adopt(task: TaskDefinition, entry: ServiceRegistryEntry, handle: ServiceHandle): ManagedService {
        val managed = ManagedService(entry.id, task, java.nio.file.Path.of(entry.workspace), entry.port)
        managed.state = ServiceState.RUNNING
        managed.startedAtEpochMs = entry.startedAtEpochMs
        // The confirmed-alive process/container check just above stands in for a
        // heartbeat: without this the watchdog would see a "never heartbeated"
        // service whose last-known start time is arbitrarily old and reap it
        // before the bridge gets a chance to report in again.
        managed.lastHeartbeatEpochMs = clock()
        managed.controlToken = entry.controlToken
        managed.handle = handle
        services[managed.id] = managed
        handle.onExit { exitCode -> onExit(managed, exitCode) }
        logger.info("Adopted surviving service {} (pid {})", managed.id, handle.pid ?: "n/a")
        eventSink("service", "info", "Adopted surviving service ${managed.id}")
        persistRegistry()
        return managed
    }

    /**
     * Rewrites the on-disk service registry from the current services map,
     * for callers outside the normal lifecycle (for example after the
     * adoption pass dropped dead entries).
     */
    fun flushRegistry() {
        persistRegistry()
    }

    private fun persistRegistry() {
        val registry = registry ?: return
        // Sequence and snapshot are taken atomically under the manager lock,
        // so the writer can drop a snapshot that was overtaken by a newer
        // one instead of overwriting it out of order.
        val (sequence, snapshot) = synchronized(this) {
            registry.nextSequence() to services.values.toList()
        }
        registry.write(sequence, snapshot)
    }

    private fun baseEnvironment(managed: ManagedService): Map<String, String> = mapOf(
        "HELIX_SERVICE_ID" to managed.id,
        "HELIX_TASK" to managed.task.name,
        "HELIX_SERVICE_PORT" to managed.port.toString(),
    )

    private fun onExit(managed: ManagedService, exitCode: Int) {
        managed.lastLogs = runCatching { managed.handle?.logs(FINAL_LOG_LINES) ?: emptyList() }
            .getOrDefault(emptyList())
        managed.state = if (managed.watchdogKilled) {
            ServiceState.FAILED
        } else if (managed.stopRequested || exitCode == 0) {
            ServiceState.STOPPED
        } else {
            ServiceState.FAILED
        }
        managed.handle = null
        managed.onlinePlayers = 0
        if (managed.state == ServiceState.FAILED) {
            logger.error(
                "Service {} failed with exit code {} — last output:\n{}",
                managed.id,
                exitCode,
                managed.lastLogs.joinToString("\n").ifBlank { "(no output captured)" },
            )
            eventSink("service", "error", "${managed.id} failed with exit code $exitCode")
        } else {
            logger.info("Service {} terminated with exit code {} ({})", managed.id, exitCode, managed.state)
            eventSink("service", "info", "${managed.id} stopped")
        }
        if (!managed.task.staticServices) {
            // A delayed exit callback of a stopped service can fire AFTER its id was
            // reused by a freshly started successor (same id → same temp workspace path).
            // Only clean up when this exact instance still owns the id's map slot, so a
            // late predecessor never wipes the successor's workspace or map entry.
            synchronized(this) {
                if (services[managed.id] === managed) {
                    workspacePreparer.deleteRecursively(managed.workspace)
                    if (managed.state == ServiceState.STOPPED) {
                        services.remove(managed.id)
                    }
                }
            }
        }
        persistRegistry()
        stopListeners.forEach { it(managed) }
    }

    private fun allocateId(task: TaskDefinition): String {
        var index = 1
        while (services.values.any { it.task.name == task.name && it.id == "${task.name}-$index" && it.active() }) {
            index++
        }
        return "${task.name}-$index"
    }

    private companion object {
        /** Log lines captured when a service terminates. */
        const val FINAL_LOG_LINES = 100

        /** Environment variable carrying the per-service bridge token. */
        const val CONTROL_TOKEN_ENV = "HELIX_CONTROL_TOKEN"
    }
}
