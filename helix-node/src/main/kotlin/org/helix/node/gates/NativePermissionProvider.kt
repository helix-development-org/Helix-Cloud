package org.helix.node.gates

import org.helix.api.permission.PermissionProvider
import org.helix.api.proxy.PermissionCheckRequest

/**
 * Default [PermissionProvider] backed by Minecraft-native permission snapshots.
 *
 * Answers from the [NativePermissionCache] the proxy bridge populates on join.
 * Abstains (`null`) for players the node has no snapshot for, so callers can
 * fall back to a safe default.
 *
 * @property cache per-player native permission snapshots.
 */
class NativePermissionProvider(private val cache: NativePermissionCache) : PermissionProvider {
    override fun resolve(request: PermissionCheckRequest): Boolean? {
        val granted = cache.granted(request.name) ?: return null
        return request.permission in granted
    }
}
