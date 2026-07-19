package org.helix.api.task

import kotlinx.serialization.Serializable

/**
 * Auto-scaling behaviour of a task.
 *
 * The node always keeps [TaskDefinition.minServiceCount] services alive.
 * When scaling is enabled it additionally starts services while the player
 * slot usage across all running services of the task reaches
 * [playerRatioThreshold], and stops idle surplus services again.
 *
 * @property enabled whether player-based scale-up and idle scale-down run.
 * @property playerRatioThreshold occupied player-slot ratio (0.0–1.0) at
 *   which an additional service is started, up to
 *   [TaskDefinition.maxServiceCount].
 * @property idleStopSeconds seconds a surplus dynamic service must be empty
 *   before it is stopped again.
 */
@Serializable
data class AutoScaleSettings(
    val enabled: Boolean = false,
    val playerRatioThreshold: Double = 0.8,
    val idleStopSeconds: Long = 300,
)
