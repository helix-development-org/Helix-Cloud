package org.helix.addons.permissions

import kotlinx.serialization.Serializable

/**
 * A permission group.
 *
 * @property name unique group name, lowercase.
 * @property weight precedence — higher-weight groups win conflicts.
 * @property default whether players without groups belong to this group.
 * @property permissions permission nodes; `*` and `prefix.*` wildcards,
 *   `-node` negates.
 * @property parents names of inherited groups.
 * @property prefix display prefix members carry in chat/tab/name tag, for
 *   example `&cAdmin &f`. The player's highest-weight group with a prefix
 *   wins — deliberately independent of permission nodes, so a `*` grant
 *   never changes how someone is displayed.
 * @property color display name color code, for example `&c`.
 */
@Serializable
data class PermissionGroup(
    val name: String,
    val weight: Int = 0,
    val default: Boolean = false,
    val permissions: List<String> = emptyList(),
    val parents: List<String> = emptyList(),
    val prefix: String = "",
    val color: String = "",
)
