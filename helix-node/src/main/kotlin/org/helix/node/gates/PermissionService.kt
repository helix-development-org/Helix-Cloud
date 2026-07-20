package org.helix.node.gates

import org.helix.api.permission.PermissionProvider
import org.helix.api.proxy.PermissionCheckRequest

/**
 * The single node-wide entry point for permission decisions.
 *
 * Permissions do not depend on any addon: when an addon has registered a
 * resolver it fully governs the decision (it *overrides* the default);
 * otherwise the Minecraft-native [PermissionProvider] decides. This keeps the
 * whole platform runnable without the permissions addon.
 *
 * @property resolvers addon-registered resolvers (the override tier).
 * @property native the Minecraft-native default provider.
 */
class PermissionService(
    private val resolvers: PermissionResolverRegistry,
    private val native: PermissionProvider,
) {
    /**
     * Decides whether a player holds a permission.
     *
     * @param request the player and permission node.
     * @return `true` if granted.
     */
    fun check(request: PermissionCheckRequest): Boolean =
        if (resolvers.hasOverrides()) {
            resolvers.evaluate(request)
        } else {
            native.resolve(request) ?: false
        }
}
