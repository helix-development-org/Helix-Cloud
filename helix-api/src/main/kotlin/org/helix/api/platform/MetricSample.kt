package org.helix.api.platform

import kotlinx.serialization.Serializable

/**
 * One point in the network's live metric history.
 *
 * The resource fields default to `null`/`-1` so samples stay wire-compatible
 * with older dashboards and pre-resource nodes.
 *
 * @property epochMs when the sample was taken.
 * @property onlinePlayers connected players across all running backends.
 * @property maxPlayers player slots across all running backends.
 * @property servicesRunning services in `RUNNING` state.
 * @property servicesTotal all known services.
 * @property avgTps average TPS across running backends, or `null` if unknown.
 * @property avgApiMs average control-API response time over the recent window,
 *  or `null` if there were no requests.
 * @property nodeCpuPercent the node process's own CPU load (percent), or `-1`.
 * @property nodeHeapUsedMb node JVM heap in use (MB), or `-1`.
 * @property nodeHeapMaxMb node JVM max heap (MB), or `-1`.
 * @property systemLoadAverage host 1-minute load average, or `-1`.
 * @property servicesCpuPercent summed CPU load of all running services, or `-1`.
 * @property servicesMemoryUsedMb summed heap-in-use of all running services (MB), or `-1`.
 * @property servicesMemoryMaxMb summed max heap of all running services (MB), or `-1`.
 */
@Serializable
data class MetricSample(
    val epochMs: Long,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val servicesRunning: Int,
    val servicesTotal: Int,
    val avgTps: Double? = null,
    val avgApiMs: Double? = null,
    val nodeCpuPercent: Double = -1.0,
    val nodeHeapUsedMb: Int = -1,
    val nodeHeapMaxMb: Int = -1,
    val systemLoadAverage: Double = -1.0,
    val servicesCpuPercent: Double = -1.0,
    val servicesMemoryUsedMb: Int = -1,
    val servicesMemoryMaxMb: Int = -1,
)
