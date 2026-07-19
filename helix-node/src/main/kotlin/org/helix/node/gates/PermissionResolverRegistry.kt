package org.helix.node.gates

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.addon.PermissionResolver
import org.helix.api.proxy.PermissionCheckRequest
import org.slf4j.LoggerFactory

/**
 * Aggregates all permission resolvers registered by addons.
 *
 * A permission is granted when any resolver grants it. A resolver that
 * throws is skipped. Without any registered resolver every check is
 * denied.
 */
class PermissionResolverRegistry {
    private val logger = LoggerFactory.getLogger(PermissionResolverRegistry::class.java)
    private val resolvers = ConcurrentHashMap<String, CopyOnWriteArrayList<PermissionResolver>>()

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
        resolvers.values.flatten().any { resolver ->
            runCatching { resolver.has(request) }
                .onFailure { logger.error("permission resolver failed for {}", request.name, it) }
                .getOrDefault(false)
        }
}
