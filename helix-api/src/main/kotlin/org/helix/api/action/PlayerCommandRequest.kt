package org.helix.api.action

import kotlinx.serialization.Serializable

/**
 * A player-executed command forwarded by a proxy bridge.
 *
 * The node resolves the matching player-command action, checks its
 * permission and invokes it with the player name as first argument.
 *
 * @property player executing player name.
 * @property command command name, equal to the action name.
 * @property arguments arguments typed by the player.
 */
@Serializable
data class PlayerCommandRequest(
    val player: String,
    val command: String,
    val arguments: List<String> = emptyList(),
)
