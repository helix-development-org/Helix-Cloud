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

/**
 * Matching rules for permission nodes.
 */
object PermissionMatcher {
    /**
     * Whether a node entry matches a requested permission.
     *
     * @param entry configured node without negation prefix, for example
     *   `*`, `helix.command.*` or `helix.maintenance.bypass`.
     * @param permission requested permission.
     * @return `true` on exact or wildcard match.
     */
    fun matches(entry: String, permission: String): Boolean = when {
        entry == "*" -> true
        entry.endsWith(".*") -> permission.startsWith(entry.dropLast(1)) || permission == entry.dropLast(2)
        else -> entry.equals(permission, ignoreCase = true)
    }

    /**
     * Evaluates one entry list: negations win over grants.
     *
     * @param entries configured nodes, possibly with `-` prefixes.
     * @param permission requested permission.
     * @return `true`/`false` on a decision, `null` when no entry matches.
     */
    fun decide(entries: List<String>, permission: String): Boolean? {
        if (entries.any { it.startsWith("-") && matches(it.substring(1), permission) }) {
            return false
        }
        if (entries.any { !it.startsWith("-") && matches(it, permission) }) {
            return true
        }
        return null
    }
}
