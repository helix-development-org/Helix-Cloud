package org.helix.addons.labymod

import kotlinx.serialization.Serializable

/**
 * One queued one-shot command for the Velocity component, delivered via
 * the `labymod.cmd` bridge value.
 *
 * The component polls the bridge values, remembers the highest sequence
 * number it has executed and runs only newer entries — on its first poll
 * it fast-forwards without executing, so stale commands are never
 * replayed after a proxy restart.
 *
 * @property seq monotonically increasing sequence number.
 * @property type command type: `emote`, `marker`, `input`, `serverswitch`
 *   or `banner`.
 * @property player receiving player name, `all` for every LabyMod user;
 *   empty when [service] scopes the command instead.
 * @property service backend service id scoping the command to the LabyMod
 *   users currently on it; empty for player-scoped commands.
 * @property args type-specific arguments.
 */
@Serializable
data class LabyCommand(
    val seq: Long,
    val type: String,
    val player: String = "",
    val service: String = "",
    val args: List<String> = emptyList(),
)

/**
 * The queue payload of the `labymod.cmd` bridge value.
 *
 * @property entries the newest commands, oldest first.
 */
@Serializable
data class LabyCommandQueue(val entries: List<LabyCommand> = emptyList())

/**
 * A LabyMod user currently online, reported by the Velocity component.
 *
 * @property name player name.
 * @property uuid player uuid.
 * @property version the reported LabyMod version.
 * @property sinceEpochMs when the presence was recorded.
 */
@Serializable
data class LabyPresence(
    val name: String,
    val uuid: String,
    val version: String,
    val sinceEpochMs: Long,
)
