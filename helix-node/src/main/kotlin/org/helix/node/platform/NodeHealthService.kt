package org.helix.node.platform

import java.lang.management.ManagementFactory
import org.helix.api.bridge.ResourceProbe
import org.helix.api.platform.NodeHealth
import org.helix.api.service.ServiceState
import org.helix.node.gates.NativePermissionCache
import org.helix.node.players.PlayerRegistry
import org.helix.node.scheduler.JobScheduler
import org.helix.node.services.ManagedService
import org.helix.node.services.ServiceManager

/**
 * Collects the node's own runtime health: process CPU/heap, host load, JVM
 * threads and GC, plus aggregated resource use of the managed services and a
 * few in-memory registry sizes.
 *
 * @property manager service lifecycle owner.
 * @property players online player registry.
 * @property permissions native permission cache.
 * @property jobs scheduled job registry.
 * @property clock epoch millis source, injectable for tests.
 */
class NodeHealthService(
    private val manager: ServiceManager,
    private val players: PlayerRegistry,
    private val permissions: NativePermissionCache,
    private val jobs: JobScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val runtimeBean = ManagementFactory.getRuntimeMXBean()
    private val threadBean = ManagementFactory.getThreadMXBean()
    private val megabyte = 1024L * 1024L

    /** Milliseconds a running service may miss its heartbeat before it counts as stale. */
    private val staleHeartbeatMillis = 15_000L

    /**
     * Aggregated resource use of all currently running services from their
     * latest heartbeats.
     *
     * @return summed cpu percent (or `-1` when unreported), heap-used MB and
     *   heap-max MB.
     */
    fun serviceResources(): ServiceResources {
        val running = manager.managedServices().filter { it.state == ServiceState.RUNNING }
        val cpu = running.mapNotNull { it.cpuPercent.takeIf { value -> value >= 0 } }
        val used = running.mapNotNull { it.memoryUsedMb.takeIf { value -> value >= 0 } }
        val max = running.mapNotNull { it.memoryMaxMb.takeIf { value -> value >= 0 } }
        return ServiceResources(
            cpuPercent = if (cpu.isEmpty()) -1.0 else round1(cpu.sum()),
            memoryUsedMb = if (used.isEmpty()) -1 else used.sum(),
            memoryMaxMb = if (max.isEmpty()) -1 else max.sum(),
        )
    }

    /**
     * A full point-in-time health snapshot of the node.
     *
     * @return the node health.
     */
    fun snapshot(): NodeHealth {
        val heapUsed = ResourceProbe.memoryUsedMb()
        val heapMax = ResourceProbe.memoryMaxMb()
        val nonHeap = (ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.used / megabyte).toInt()
        val gc = ManagementFactory.getGarbageCollectorMXBeans()
        val services = manager.managedServices()
        val resources = serviceResources()
        return NodeHealth(
            uptimeMs = runtimeBean.uptime,
            nodeCpuPercent = ResourceProbe.cpuPercent(),
            heapUsedMb = heapUsed,
            heapMaxMb = heapMax,
            heapPercent = if (heapMax > 0) round1(heapUsed * 100.0 / heapMax) else -1.0,
            nonHeapUsedMb = nonHeap,
            systemLoadAverage = round2(ManagementFactory.getOperatingSystemMXBean().systemLoadAverage),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            threadCount = threadBean.threadCount,
            peakThreadCount = threadBean.peakThreadCount,
            gcCount = gc.sumOf { it.collectionCount.coerceAtLeast(0) },
            gcTimeMs = gc.sumOf { it.collectionTime.coerceAtLeast(0) },
            servicesRunning = services.count { it.state == ServiceState.RUNNING },
            servicesTotal = services.size,
            onlinePlayers = players.online().size,
            permissionCacheSize = permissions.snapshot().size,
            scheduledJobs = jobs.all().size,
            servicesCpuPercent = resources.cpuPercent,
            servicesMemoryUsedMb = resources.memoryUsedMb,
            servicesMemoryMaxMb = resources.memoryMaxMb,
            staleHeartbeats = services.count { isStale(it) },
        )
    }

    private fun isStale(service: ManagedService): Boolean {
        if (service.state != ServiceState.RUNNING) {
            return false
        }
        val last = service.lastHeartbeatEpochMs ?: return true
        return clock() - last > staleHeartbeatMillis
    }

    private fun round1(value: Double) = Math.round(value * 10.0) / 10.0

    private fun round2(value: Double) = Math.round(value * 100.0) / 100.0

    /**
     * Aggregated resource use of the running services.
     *
     * @property cpuPercent summed CPU load, or `-1`.
     * @property memoryUsedMb summed heap-in-use (MB), or `-1`.
     * @property memoryMaxMb summed max heap (MB), or `-1`.
     */
    data class ServiceResources(
        val cpuPercent: Double,
        val memoryUsedMb: Int,
        val memoryMaxMb: Int,
    )
}
