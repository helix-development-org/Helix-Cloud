package org.helix.api.permission

import org.helix.api.proxy.PermissionCheckRequest

/**
 * A source of permission decisions, independent of any addon.
 *
 * Providers are composed by the node: the Minecraft-native provider is the
 * default, and an addon (for example the permissions addon) can take over.
 * A provider may abstain by returning `null`, letting the next source decide.
 */
fun interface PermissionProvider {
    /**
     * Decides a permission check.
     *
     * @param request the player and permission node in question.
     * @return `true` to grant, `false` to deny, or `null` to abstain.
     */
    fun resolve(request: PermissionCheckRequest): Boolean?
}
