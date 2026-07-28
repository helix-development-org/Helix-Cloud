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
 * @property bridgeInvocable whether a Paper/Velocity component holding a
 *   per-service token may invoke this action via `POST /internal/action`.
 *   Actions without a declared [permission] otherwise require the static
 *   admin token on `POST /api/v1/actions`, which a per-service token can
 *   never satisfy — this flag is the explicit opt-in for actions meant to
 *   be called by a plugin's own backend component instead of the CLI or
 *   dashboard, for example a HXA's node-backed storage proxy.
 */
@Serializable
data class ActionDescriptor @JvmOverloads constructor(
    val name: String,
    val description: String,
    val usage: String,
    val playerCommand: Boolean = false,
    val permission: String? = null,
    val bridgeInvocable: Boolean = false,
)
