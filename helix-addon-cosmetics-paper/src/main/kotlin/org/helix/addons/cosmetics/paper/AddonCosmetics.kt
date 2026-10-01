package org.helix.addons.cosmetics.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Cosmetics for players with the Helix LabyMod addon: their client draws wings and headwear on the player model
 * itself (no lag at all). They get everyone's cosmetics as data over `helix:cosmetics` and are excluded from the
 * server-side rendering (fake equipment, wing displays) so nothing is drawn twice.
 *
 * @property plugin owning plugin (outgoing channel).
 */
class AddonCosmetics(private val plugin: Plugin) {
    private var snapshot: Map<UUID, Pair<String?, String?>> = emptyMap()
    private val informed = HashSet<UUID>()

    /** Registers the outgoing channel. */
    fun start() {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
    }

    /**
     * @param player a viewer.
     * @return whether the viewer draws cosmetics itself (has the addon with cosmetics on).
     */
    fun drawsItself(player: Player): Boolean = CHANNEL in player.listeningPluginChannels

    /**
     * Sends changes to addon viewers; new addon viewers get everything.
     *
     * @param online online players.
     * @param wornBy wings and headwear model names of a player.
     */
    fun update(online: Collection<Player>, wornBy: (Player) -> Pair<String?, String?>) {
        val now = online.associate { it.uniqueId to wornBy(it) }.filterValues { it.first != null || it.second != null }
        val changed = now.filter { (id, worn) -> snapshot[id] != worn }
        val removed = snapshot.keys - now.keys
        snapshot = now
        informed.retainAll(online.map { it.uniqueId }.toSet())
        online.filter(::drawsItself).forEach { viewer ->
            val message = if (informed.add(viewer.uniqueId)) message(now, emptySet()) else message(changed, removed)
            if (message != null) viewer.sendPluginMessage(plugin, CHANNEL, message.toByteArray(Charsets.UTF_8))
        }
    }

    /** Message building (pure, testable). */
    companion object {
        /** Cosmetics channel of the Helix LabyMod addon. */
        const val CHANNEL = "helix:cosmetics"

        /**
         * @param players players with their wings / headwear model names.
         * @param removed players without cosmetics any more.
         * @return the JSON message, or null if there is nothing to send.
         */
        fun message(players: Map<UUID, Pair<String?, String?>>, removed: Set<UUID>): String? {
            if (players.isEmpty() && removed.isEmpty()) return null
            val entries = players.entries.joinToString(",") { (id, worn) ->
                "\"$id\":{\"wings\":${json(worn.first)},\"headwear\":${json(worn.second)}}"
            }
            val gone = removed.joinToString(",") { "\"$it\"" }
            return "{\"players\":{$entries},\"remove\":[$gone]}"
        }

        private fun json(value: String?): String = if (value == null) "null" else "\"$value\""
    }
}
