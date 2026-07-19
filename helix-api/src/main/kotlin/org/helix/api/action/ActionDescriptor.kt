package org.helix.api.action

import kotlinx.serialization.Serializable

/**
 * Describes a registered action.
 *
 * @property name unique action name, dot-separated, for example `service.start`.
 *   Player-command actions use command-safe names without dots, for
 *   example `friend`.
 * @property description one-line human readable summary.
 * @property usage argument hint, for example `service.start <task>`.
 * @property playerCommand whether proxy bridges register this action as an
 *   in-game command; the handler receives the player name as first
 *   argument, followed by the typed arguments.
 * @property permission permission node required to invoke this action as a
 *   player command; `null` means everyone.
 */
@Serializable
data class ActionDescriptor(
    val name: String,
    val description: String,
    val usage: String,
    val playerCommand: Boolean = false,
    val permission: String? = null,
)
