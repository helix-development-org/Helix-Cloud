package org.helix.node.display

import org.helix.api.addon.DisplayResolver
import org.helix.api.display.DisplayProfile
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Aggregates all display resolvers registered by addons.
 *
 * Profiles are merged per component: the first resolver providing a
 * non-empty prefix supplies the prefix, likewise for name (nick), suffix
 * and color. This lets independent addons compose one display name — by
 * convention groups own the prefix, a nick addon owns the name and clans
 * own the suffix. A resolver that throws is skipped. Without any resolver
 * every player gets the empty profile.
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
     * Resolves a player's display profile by merging all resolver results.
     *
     * An [DisplayProfile.exclusive] profile short-circuits the merge and is
     * returned alone — a disguise (nick) must not leak the group prefix or
     * clan tag of the real identity.
     *
     * @param name player name.
     * @return the exclusive profile if any resolver claims one, otherwise
     *   the merged profile (first non-empty value per component).
     */
    fun resolve(name: String): DisplayProfile {
        var merged = DisplayProfile()
        resolvers.values.flatten().forEach { resolver ->
            val profile = runCatching { resolver.resolve(name) }
                .onFailure { logger.error("display resolver failed for {}", name, it) }
                .getOrNull() ?: return@forEach
            if (profile.exclusive) {
                return profile
            }
            merged = DisplayProfile(
                prefix = merged.prefix.ifEmpty { profile.prefix },
                name = merged.name.ifEmpty { profile.name },
                suffix = merged.suffix.ifEmpty { profile.suffix },
                color = merged.color.ifEmpty { profile.color },
            )
        }
        return merged
    }
}
