package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * A command a proxy bridge picks up from the node on its next sync.
 *
 * @property type command type: `kick`, `message`, `broadcast` or `connect`.
 * @property player target player name; `*` for broadcasts.
 * @property reason kick reason or message text; `&` color codes are
 *   rendered by the bridge. Used as fallback when [translationKey] is unset
 *   or unknown to the bridge.
 * @property translationKey optional translation key the bridge resolves in
 *   each receiving player's language before rendering.
 * @property params placeholder name to value pairs substituted into the
 *   resolved translation.
 * @property target service id a `connect` command moves the player to;
 *   `null` for every other type.
 */
@Serializable
data class ProxyCommand(
    val type: String,
    val player: String,
    val reason: String? = null,
    val translationKey: String? = null,
    val params: Map<String, String> = emptyMap(),
    val target: String? = null,
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
         * Creates a server switch: the proxy connects the player to [target].
         *
         * @param player target player name.
         * @param target service id of the destination server.
         * @return the connect [ProxyCommand].
         */
        fun connect(player: String, target: String): ProxyCommand =
            ProxyCommand(type = "connect", player = player, target = target)

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
