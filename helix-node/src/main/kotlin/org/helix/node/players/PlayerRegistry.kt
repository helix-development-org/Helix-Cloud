package org.helix.node.players

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.addon.PlayerListener
import org.helix.api.player.OnlinePlayer
import org.helix.api.player.PlayerEvent
import org.slf4j.LoggerFactory

/**
 * Tracks all players connected to the network.
 *
 * Proxy bridges report joins and leaves; addons read the registry and
 * receive events through registered listeners. When a proxy service
 * terminates, its players are dropped.
 *
 * @property clock epoch millis source, injectable for tests.
 */
class PlayerRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(PlayerRegistry::class.java)
    private val players = ConcurrentHashMap<String, OnlinePlayer>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<PlayerListener>>()

    /**
     * Applies a bridge-reported join or leave.
     *
     * @param event the reported event.
     * @return `true` when the event type was known.
     */
    fun handle(event: PlayerEvent): Boolean {
        val key = event.name.lowercase()
        when (event.type) {
            "join" -> {
                val player = OnlinePlayer(
                    name = event.name,
                    uuid = event.uuid,
                    proxyServiceId = event.proxyServiceId,
                    joinedAtEpochMs = clock(),
                )
                players[key] = player
                notify { it.onJoin(player) }
            }
            "leave" -> {
                val player = players.remove(key) ?: return true
                notify { it.onLeave(player) }
            }
            else -> return false
        }
        return true
    }

    /**
     * Lists all online players.
     *
     * @return players sorted by name.
     */
    fun online(): List<OnlinePlayer> = players.values.sortedBy { it.name.lowercase() }

    /**
     * Restores the roster after a node restart, without firing join events.
     *
     * Subsequent bridge-reported joins and leaves correct the roster again.
     *
     * @param restored the players online before the restart.
     */
    fun restore(restored: List<OnlinePlayer>) {
        restored.forEach { player -> players.putIfAbsent(player.name.lowercase(), player) }
    }

    /**
     * Looks up an online player.
     *
     * @param name player name, case-insensitive.
     * @return the player or `null`.
     */
    fun find(name: String): OnlinePlayer? = players[name.lowercase()]

    /**
     * Registers a listener under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param listener receives join and leave events.
     */
    fun register(owner: String, listener: PlayerListener) {
        listeners.computeIfAbsent(owner) { CopyOnWriteArrayList() }.add(listener)
    }

    /**
     * Removes all listeners of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unregisterOwner(owner: String) {
        listeners.remove(owner)
    }

    /**
     * Drops all players of a terminated proxy service.
     *
     * @param proxyServiceId the terminated proxy.
     */
    fun dropProxy(proxyServiceId: String) {
        players.values.filter { it.proxyServiceId == proxyServiceId }.forEach { player ->
            players.remove(player.name.lowercase())
            notify { it.onLeave(player) }
        }
    }

    private fun notify(call: (PlayerListener) -> Unit) {
        listeners.values.flatten().forEach { listener ->
            runCatching { call(listener) }
                .onFailure { logger.error("player listener failed", it) }
        }
    }
}
