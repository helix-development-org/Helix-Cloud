package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * A command a proxy bridge picks up from the node on its next sync.
 *
 * @property type command type, currently only `kick`.
 * @property player target player name.
 * @property reason human readable reason shown to the player.
 */
@Serializable
data class ProxyCommand(
    val type: String,
    val player: String,
    val reason: String? = null,
) {
    companion object {
        /**
         * Creates a kick command.
         *
         * @param player target player name.
         * @param reason message shown to the kicked player.
         * @return the kick [ProxyCommand].
         */
        fun kick(player: String, reason: String?): ProxyCommand =
            ProxyCommand(type = "kick", player = player, reason = reason)
    }
}
