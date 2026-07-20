package org.helix.addons.permissions

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
