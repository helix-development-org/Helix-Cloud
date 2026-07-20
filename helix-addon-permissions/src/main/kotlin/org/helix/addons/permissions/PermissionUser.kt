package org.helix.addons.permissions

import kotlinx.serialization.Serializable

/**
 * A player's permission profile.
 *
 * @property name player name, lowercase.
 * @property groups group memberships.
 * @property permissions personal permission nodes, highest precedence.
 */
@Serializable
data class PermissionUser(
    val name: String,
    val groups: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
)
