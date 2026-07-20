package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * A command a proxy bridge picks up from the node on its next sync.
 *
 * @property type command type: `kick`, `message` or `broadcast`.
 * @property player target player name; `*` for broadcasts.
 * @property reason kick reason or message text; `&` color codes are
 *   rendered by the bridge.
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
        @JvmStatic
        fun kick(player: String, reason: String?): ProxyCommand =
            ProxyCommand(type = "kick", player = player, reason = reason)

        /**
         * Creates a chat message command.
         *
         * @param player target player name.
         * @param text message text.
         * @return the message [ProxyCommand].
         */
        fun message(player: String, text: String): ProxyCommand =
            ProxyCommand(type = "message", player = player, reason = text)

        /**
         * Creates a network-wide broadcast command.
         *
         * @param text message text.
         * @return the broadcast [ProxyCommand].
         */
        fun broadcast(text: String): ProxyCommand =
            ProxyCommand(type = "broadcast", player = "*", reason = text)
    }
}
