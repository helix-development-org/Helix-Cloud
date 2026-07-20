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
 */
@Serializable
data class PermissionGroup(
    val name: String,
    val weight: Int = 0,
    val default: Boolean = false,
    val permissions: List<String> = emptyList(),
    val parents: List<String> = emptyList(),
)
