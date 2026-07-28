package org.helix.addons.permissions

import kotlinx.serialization.Serializable

/**
 * A player's permission profile.
 *
 * Stored keyed on [uuid] once known, so a freed and Mojang-recycled name
 * never inherits the previous owner's groups or grants — see
 * [PermissionStore]. [name] is kept alongside purely for display; it plays
 * no role in identity once [uuid] is set.
 *
 * @property name player's last-known name, lowercase.
 * @property groups permanent group memberships.
 * @property permissions permanent personal permission nodes, highest precedence.
 * @property timedPermissions personal permission nodes with an expiry.
 * @property timedGroups group memberships with an expiry.
 * @property uuid player's uuid, or `null` when not yet known.
 */
@Serializable
data class PermissionUser(
    val name: String,
    val groups: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val timedPermissions: List<TimedGrant> = emptyList(),
    val timedGroups: List<TimedGrant> = emptyList(),
    val uuid: String? = null,
) {
    /**
     * Whether the profile carries no assignments at all.
     *
     * @return `true` when every permanent and timed list is empty.
     */
    fun isEmpty(): Boolean =
        groups.isEmpty() && permissions.isEmpty() && timedPermissions.isEmpty() && timedGroups.isEmpty()
}
