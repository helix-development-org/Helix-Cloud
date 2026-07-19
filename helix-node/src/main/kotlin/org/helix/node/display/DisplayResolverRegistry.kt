package org.helix.node.display

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.addon.DisplayResolver
import org.helix.api.display.DisplayProfile
import org.slf4j.LoggerFactory

/**
 * Aggregates all display resolvers registered by addons.
 *
 * The first resolver returning a profile wins; a resolver that throws is
 * skipped. Without any resolver every player gets the empty profile.
 */
class DisplayResolverRegistry {
    private val logger = LoggerFactory.getLogger(DisplayResolverRegistry::class.java)
    private val resolvers = ConcurrentHashMap<String, CopyOnWriteArrayList<DisplayResolver>>()

    /**
     * Registers a resolver under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param resolver resolves display profiles.
     */
    fun register(owner: String, resolver: DisplayResolver) {
        resolvers.computeIfAbsent(owner) { CopyOnWriteArrayList() }.add(resolver)
    }

    /**
     * Removes all resolvers of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unregisterOwner(owner: String) {
        resolvers.remove(owner)
    }

    /**
     * Resolves a player's display profile.
     *
     * @param name player name.
     * @return the first non-null resolver result, or the empty profile.
     */
    fun resolve(name: String): DisplayProfile {
        resolvers.values.flatten().forEach { resolver ->
            val profile = runCatching { resolver.resolve(name) }
                .onFailure { logger.error("display resolver failed for {}", name, it) }
                .getOrNull()
            if (profile != null) {
                return profile
            }
        }
        return DisplayProfile()
    }
}
