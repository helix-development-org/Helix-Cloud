package org.helix.node.gates

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.addon.ProfileInfoEntry
import org.helix.api.addon.ProfileInfoProvider
import org.slf4j.LoggerFactory

/**
 * Aggregates all [ProfileInfoProvider]s registered by addons, backing the
 * profile addon's read-only info cards.
 *
 * A provider that throws is skipped, so one broken addon cannot break the
 * whole profile view.
 */
class ProfileInfoRegistry {
    private val logger = LoggerFactory.getLogger(ProfileInfoRegistry::class.java)
    private val providers = ConcurrentHashMap<String, CopyOnWriteArrayList<ProfileInfoProvider>>()

    /**
     * Registers a provider under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param provider contributes display lines for a player.
     */
    fun register(owner: String, provider: ProfileInfoProvider) {
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
     * Owner id to display lines, for every owner that has anything to show.
     *
     * @param player player name.
     * @return owner id to that owner's display lines.
     */
    fun infoFor(player: String): Map<String, List<ProfileInfoEntry>> = buildMap {
        providers.forEach { (owner, ownerProviders) ->
            val lines = ownerProviders.flatMap { provider ->
                runCatching { provider.infoFor(player) }
                    .onFailure { logger.error("profile-info lookup failed for owner {}", owner, it) }
                    .getOrDefault(emptyList())
            }
            if (lines.isNotEmpty()) {
                put(owner, lines)
            }
        }
    }
}
