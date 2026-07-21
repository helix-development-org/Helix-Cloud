package org.helix.api.platform

import kotlinx.serialization.Serializable

/**
 * One point in the network's live metric history.
 *
 * @property epochMs when the sample was taken.
 * @property onlinePlayers connected players across all running backends.
 * @property maxPlayers player slots across all running backends.
 * @property servicesRunning services in `RUNNING` state.
 * @property servicesTotal all known services.
 * @property avgTps average TPS across running backends, or `null` if unknown.
 */
@Serializable
data class MetricSample(
    val epochMs: Long,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val servicesRunning: Int,
    val servicesTotal: Int,
    val avgTps: Double? = null,
)
