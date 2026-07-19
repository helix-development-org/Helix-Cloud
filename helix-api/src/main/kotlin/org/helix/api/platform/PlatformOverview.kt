package org.helix.api.platform

import kotlinx.serialization.Serializable

/**
 * Aggregated node status for dashboards and the CLI.
 *
 * @property version Helix-Cloud version.
 * @property taskCount number of configured tasks.
 * @property servicesRunning services currently in `RUNNING` state.
 * @property servicesTotal all known services regardless of state.
 * @property onlinePlayers connected players across all backends.
 * @property maxPlayers player slots across all running backends.
 */
@Serializable
data class PlatformOverview(
    val version: String,
    val taskCount: Int,
    val servicesRunning: Int,
    val servicesTotal: Int,
    val onlinePlayers: Int,
    val maxPlayers: Int,
)
