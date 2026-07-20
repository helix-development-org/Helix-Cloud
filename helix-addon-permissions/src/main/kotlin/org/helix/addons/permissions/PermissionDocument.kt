package org.helix.addons.permissions

import kotlinx.serialization.Serializable

/**
 * Root document persisted as `permissions.json`.
 *
 * @property groups all groups.
 * @property users all users with explicit data.
 */
@Serializable
data class PermissionDocument(
    val groups: List<PermissionGroup> = emptyList(),
    val users: List<PermissionUser> = emptyList(),
)
