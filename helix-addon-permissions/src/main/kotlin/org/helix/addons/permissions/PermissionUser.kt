package org.helix.addons.permissions

import kotlinx.serialization.Serializable

/**
 * A player's permission profile.
 *
 * @property name player name, lowercase.
 * @property groups permanent group memberships.
 * @property permissions permanent personal permission nodes, highest precedence.
 * @property timedPermissions personal permission nodes with an expiry.
 * @property timedGroups group memberships with an expiry.
 */
@Serializable
data class PermissionUser(
    val name: String,
    val groups: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val timedPermissions: List<TimedGrant> = emptyList(),
    val timedGroups: List<TimedGrant> = emptyList(),
) {
    /**
     * Whether the profile carries no assignments at all.
     *
     * @return `true` when every permanent and timed list is empty.
     */
    fun isEmpty(): Boolean =
        groups.isEmpty() && permissions.isEmpty() && timedPermissions.isEmpty() && timedGroups.isEmpty()
}
