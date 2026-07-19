package org.helix.api.addon

import org.helix.api.proxy.PermissionCheckRequest

/**
 * Answers permission questions for players.
 *
 * Resolvers are registered by addons (for example a permission addon);
 * bridges and other addons ask the node, which aggregates all resolvers —
 * a permission is granted when any resolver grants it. Without any
 * registered resolver every check is denied.
 */
fun interface PermissionResolver {
    /**
     * Evaluates one permission question.
     *
     * @param request player and permission node.
     * @return `true` when the permission is granted.
     */
    fun has(request: PermissionCheckRequest): Boolean
}
