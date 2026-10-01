package org.helix.bridge.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds the HUD widgets of the Helix LabyMod addon over the plugin channel `helix:hud`: the network balance
 * (`balance`). Only players whose client registered the channel (= have the addon) get messages, and only when the
 * value changed. Other plugins (e.g. MineClash) send their own keys on the same channel.
 *
 * @property plugin owning plugin (registers the outgoing channel).
 */
class LabyHud(private val plugin: Plugin) {
    private val sent = ConcurrentHashMap<String, String>()

    /** Registers the outgoing channel. */
    fun start() {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
    }

    /**
     * Sends changed balances to addon users.
     *
     * @param players online players.
     * @param balanceOf the formatted balance of a player (empty: none).
     */
    fun update(players: Collection<Player>, balanceOf: (Player) -> String) {
        players.forEach { player ->
            if (CHANNEL !in player.listeningPluginChannels) return@forEach
            val balance = balanceOf(player)
            val key = player.uniqueId.toString()
            if (sent[key] == balance) return@forEach
            sent[key] = balance
            val message = if (balance.isEmpty()) remove("balance") else values("balance" to balance)
            player.sendPluginMessage(plugin, CHANNEL, message.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Forgets a player (quit).
     *
     * @param player the player.
     */
    fun forget(player: Player) {
        sent.remove(player.uniqueId.toString())
    }

    /** Message building (pure, testable). */
    companion object {
        /** The HUD channel of the Helix LabyMod addon. */
        const val CHANNEL = "helix:hud"

        /**
         * @param entries key to formatted value.
         * @return a `values` message (labels come from the addon's translations).
         */
        fun values(vararg entries: Pair<String, String>): String =
            entries.joinToString(",", "{\"values\":{", "}}") { (key, value) -> "\"${escape(key)}\":{\"value\":\"${escape(value)}\"}" }

        /**
         * @param keys keys to remove.
         * @return a `remove` message.
         */
        fun remove(vararg keys: String): String = keys.joinToString(",", "{\"remove\":[", "]}") { "\"${escape(it)}\"" }

        private fun escape(text: String): String = buildString {
            text.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
    }
}
