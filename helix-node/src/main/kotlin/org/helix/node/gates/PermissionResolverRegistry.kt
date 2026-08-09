package org.helix.node.gates

import org.helix.api.addon.PermissionResolver
import org.helix.api.proxy.PermissionCheckRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Aggregates all permission resolvers registered by addons.
 *
 * A permission is granted when any resolver grants it. A resolver that
 * throws is skipped. Whether any resolver is registered decides — via
 * [PermissionService] — whether addons or the Minecraft-native default
 * governs permissions.
 */
class PermissionResolverRegistry {
    private val logger = LoggerFactory.getLogger(PermissionResolverRegistry::class.java)
    private val resolvers = ConcurrentHashMap<String, CopyOnWriteArrayList<PermissionResolver>>()

    /**
     * Whether any addon has registered a permission resolver.
     *
     * @return `true` if at least one resolver is active.
     */
    fun hasOverrides(): Boolean = resolvers.values.any { it.isNotEmpty() }

    /**
     * Registers a resolver under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param resolver evaluated on every permission question.
     */
    fun register(owner: String, resolver: PermissionResolver) {
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
     * Evaluates a permission question against all resolvers.
     *
     * @param request player and permission node.
     * @return `true` when any resolver grants.
     */
    fun evaluate(request: PermissionCheckRequest): Boolean =
        resolvers.values.any { owned ->
            owned.any { resolver ->
                runCatching { resolver.has(request) }
                    .onFailure { logger.error("permission resolver failed for {}", request.name, it) }
                    .getOrDefault(false)
            }
        }
}
