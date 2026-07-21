package org.helix.node.services

import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.execution.ExecutorType
import org.helix.api.service.ServiceInfo
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.tasks.TaskStore
import org.slf4j.LoggerFactory

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
 */
class ServiceManager(
    private val taskStore: TaskStore,
    private val workspacePreparer: WorkspacePreparer,
    private val executors: Map<ExecutorType, ServiceExecutor>,
    private val environmentProvider: (ManagedService) -> Map<String, String> = { emptyMap() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val eventSink: (category: String, level: String, message: String) -> Unit = { _, _, _ -> },
) {
    private val logger = LoggerFactory.getLogger(ServiceManager::class.java)
    private val services = linkedMapOf<String, ManagedService>()
    private val portAllocator = PortAllocator()
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
        val managed = synchronized(this) {
            require(activeCount(taskName) < task.maxServiceCount) {
                "task $taskName already runs ${task.maxServiceCount} services"
            }
            val id = allocateId(task)
            val port = portAllocator.allocate(
                task.startPort,
                services.values.filter { it.active() }.map { it.port }.toSet(),
            )
            ManagedService(id, task, workspacePreparer.workspaceFor(task, id), port)
                .also { services[it.id] = it }
        }
        return runCatching { launch(managed) }
            .onFailure { failure ->
                managed.state = ServiceState.FAILED
                logger.error("Failed to start {}", managed.id, failure)
            }
            .getOrThrow()
    }

    /**
     * Requests a graceful stop.
     *
     * @param id the service id.
     * @return `true` if the service was running and the stop was issued.
     */
    fun stopService(id: String): Boolean {
        val managed = find(id) ?: return false
        val handle = managed.handle
        if (handle == null || !managed.active()) {
            return false
        }
        managed.stopRequested = true
        managed.state = ServiceState.STOPPING
        logger.info("Stopping {}", id)
        handle.stop()
        return true
    }

    /**
     * Kills a service immediately.
     *
     * @param id the service id.
     * @return `true` if the service was running.
     */
    fun killService(id: String): Boolean {
        val managed = find(id) ?: return false
        val handle = managed.handle ?: return false
        if (!managed.active()) {
            return false
        }
        managed.stopRequested = true
        managed.state = ServiceState.STOPPING
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
        if (managed.state == ServiceState.STARTING) {
            managed.state = ServiceState.RUNNING
            logger.info("Service {} is now RUNNING", managed.id)
            eventSink("service", "info", "${managed.id} is now running")
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
        val spec = ServiceStartSpec(
            serviceId = managed.id,
            task = task,
            workspace = managed.workspace,
            port = managed.port,
            environmentVariables = baseEnvironment(managed) + environmentProvider(managed),
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
        return managed.toInfo()
    }

    private fun baseEnvironment(managed: ManagedService): Map<String, String> = mapOf(
        "HELIX_SERVICE_ID" to managed.id,
        "HELIX_TASK" to managed.task.name,
        "HELIX_SERVICE_PORT" to managed.port.toString(),
    )

    private fun onExit(managed: ManagedService, exitCode: Int) {
        managed.lastLogs = runCatching { managed.handle?.logs(FINAL_LOG_LINES) ?: emptyList() }
            .getOrDefault(emptyList())
        managed.state = if (managed.stopRequested || exitCode == 0) {
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
            workspacePreparer.deleteRecursively(managed.workspace)
            // Failed services stay visible (with their captured logs) for
            // diagnosis; their record is replaced on the next start.
            if (managed.state == ServiceState.STOPPED) {
                synchronized(this) { services.remove(managed.id) }
            }
        }
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
    }
}
