package org.helix.node.launcher

import kotlinx.serialization.Serializable
import org.helix.api.platform.MetricSample
import org.helix.api.player.OnlinePlayer

/**
 * Runtime state written right before a backend restart and restored by the
 * successor process, so in-memory caches survive the restart.
 *
 * @property maintenance whether network maintenance was enabled.
 * @property players the roster of online players.
 * @property nativePermissions per-player native permission snapshots.
 * @property jobLastRuns last-run timestamps of scheduled jobs.
 * @property metrics recorded metric samples for the dashboard graphs.
 */
@Serializable
data class RestartState(
    val maintenance: Boolean = false,
    val players: List<OnlinePlayer> = emptyList(),
    val nativePermissions: Map<String, Set<String>> = emptyMap(),
    val jobLastRuns: Map<String, Long> = emptyMap(),
    val metrics: List<MetricSample> = emptyList(),
)
