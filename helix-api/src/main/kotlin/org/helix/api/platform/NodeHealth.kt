package org.helix.api.platform

import kotlinx.serialization.Serializable

/**
 * Point-in-time health of the node process itself — the orchestrator's own
 * resource usage and internal state, distinct from the services it manages.
 *
 * @property uptimeMs milliseconds since the node JVM started.
 * @property nodeCpuPercent the node process's CPU load (percent), or `-1`.
 * @property heapUsedMb node JVM heap in use (MB).
 * @property heapMaxMb node JVM max heap (MB).
 * @property heapPercent heap utilisation (percent of max).
 * @property nonHeapUsedMb node JVM non-heap memory in use (MB).
 * @property systemLoadAverage host 1-minute load average, or `-1`.
 * @property availableProcessors CPU cores available to the JVM.
 * @property threadCount live JVM thread count.
 * @property peakThreadCount peak JVM thread count since start.
 * @property gcCount total garbage collections since start.
 * @property gcTimeMs total time spent in garbage collection (ms).
 * @property servicesRunning services currently `RUNNING` (in-memory registry).
 * @property servicesTotal services tracked in memory (any state).
 * @property onlinePlayers players in the online registry.
 * @property permissionCacheSize cached native permission snapshots.
 * @property scheduledJobs configured recurring jobs.
 * @property servicesCpuPercent summed CPU load of running services, or `-1`.
 * @property servicesMemoryUsedMb summed heap-in-use of running services (MB), or `-1`.
 * @property servicesMemoryMaxMb summed max heap of running services (MB), or `-1`.
 * @property staleHeartbeats running services whose last heartbeat is overdue.
 */
@Serializable
data class NodeHealth(
    val uptimeMs: Long,
    val nodeCpuPercent: Double,
    val heapUsedMb: Int,
    val heapMaxMb: Int,
    val heapPercent: Double,
    val nonHeapUsedMb: Int,
    val systemLoadAverage: Double,
    val availableProcessors: Int,
    val threadCount: Int,
    val peakThreadCount: Int,
    val gcCount: Long,
    val gcTimeMs: Long,
    val servicesRunning: Int,
    val servicesTotal: Int,
    val onlinePlayers: Int,
    val permissionCacheSize: Int,
    val scheduledJobs: Int,
    val servicesCpuPercent: Double,
    val servicesMemoryUsedMb: Int,
    val servicesMemoryMaxMb: Int,
    val staleHeartbeats: Int,
)
