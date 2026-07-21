package org.helix.node.scheduler

import kotlinx.serialization.Serializable

/**
 * A recurring job that invokes a node action on a schedule.
 *
 * A job is either interval-based ([everyMinutes] > 0) or daily at a wall-clock
 * time ([dailyAt] = `HH:mm`); if both are set, the interval wins.
 *
 * @property id unique job id.
 * @property action name of the action to invoke (for example `player.broadcast`).
 * @property arguments positional arguments passed to the action.
 * @property everyMinutes run every N minutes; `0` disables the interval schedule.
 * @property dailyAt run once per day at `HH:mm`; `null` disables the daily schedule.
 * @property enabled whether the job runs.
 */
@Serializable
data class ScheduledJob(
    val id: String,
    val action: String,
    val arguments: List<String> = emptyList(),
    val everyMinutes: Int = 0,
    val dailyAt: String? = null,
    val enabled: Boolean = true,
)
