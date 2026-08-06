package org.helix.node.gates

import org.helix.api.addon.PlayerDataProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Aggregates all [PlayerDataProvider]s registered by addons, backing the
 * GDPR export/delete actions.
 *
 * A provider that throws is skipped for export (so one broken addon
 * cannot break the whole export) and counted as unsuccessful for delete.
 */
class PlayerDataRegistry {
    private val logger = LoggerFactory.getLogger(PlayerDataRegistry::class.java)
    private val providers = ConcurrentHashMap<String, CopyOnWriteArrayList<PlayerDataProvider>>()

    /**
     * Registers a provider under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param provider exports and deletes the owner's data for a player.
     */
    fun register(owner: String, provider: PlayerDataProvider) {
        providers.computeIfAbsent(owner) { CopyOnWriteArrayList() }.add(provider)
    }

    /**
     * Removes all providers of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unregisterOwner(owner: String) {
        providers.remove(owner)
    }

    /**
     * Owner id to raw JSON export, for every owner that holds data about
     * the player.
     *
     * @param player player name.
     * @return owner id to the provider's JSON export.
     */
    fun export(player: String): Map<String, String> = buildMap {
        providers.forEach { (owner, ownerProviders) ->
            ownerProviders.forEach { provider ->
                runCatching { provider.export(player) }
                    .onFailure { logger.error("player-data export failed for owner {}", owner, it) }
                    .getOrNull()
                    ?.let { put(owner, it) }
            }
        }
    }

    /**
     * Deletes the player's data from every registered provider.
     *
     * @param player player name.
     * @return owner ids that actually held (and removed) data.
     */
    fun delete(player: String): List<String> = buildList {
        providers.forEach { (owner, ownerProviders) ->
            ownerProviders.forEach { provider ->
                val removed = runCatching { provider.delete(player) }
                    .onFailure { logger.error("player-data delete failed for owner {}", owner, it) }
                    .getOrDefault(false)
                if (removed) {
                    add(owner)
                }
            }
        }
    }
}
