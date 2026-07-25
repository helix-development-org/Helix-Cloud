package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * A command a proxy bridge picks up from the node on its next sync.
 *
 * @property type command type: `kick`, `message` or `broadcast`.
 * @property player target player name; `*` for broadcasts.
 * @property reason kick reason or message text; `&` color codes are
 *   rendered by the bridge. Used as fallback when [translationKey] is unset
 *   or unknown to the bridge.
 * @property translationKey optional translation key the bridge resolves in
 *   each receiving player's language before rendering.
 * @property params placeholder name to value pairs substituted into the
 *   resolved translation.
 */
@Serializable
data class ProxyCommand(
    val type: String,
    val player: String,
    val reason: String? = null,
    val translationKey: String? = null,
    val params: Map<String, String> = emptyMap(),
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

        /**
         * Creates a network-wide broadcast resolved per receiving player.
         *
         * @param key translation key resolved in each player's language.
         * @param fallback text used when the bridge does not know the key.
         * @param params placeholder name to value pairs.
         * @return the broadcast [ProxyCommand].
         */
        fun broadcastKey(key: String, fallback: String, params: Map<String, String> = emptyMap()): ProxyCommand =
            ProxyCommand(type = "broadcast", player = "*", reason = fallback, translationKey = key, params = params)
    }
}
