package org.helix.addons.permissions

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * JSON-file backed permission storage with the resolution logic.
 *
 * Precedence, highest first: personal user permissions, then the user's
 * groups by descending weight, each group followed by its inherited
 * parents (depth-first, cycles ignored). At every level an explicit
 * negation (`-node`) beats a grant. Players without groups belong to all
 * `default` groups.
 *
 * @property file the `permissions.json` path.
 */
class PermissionStore(private val file: Path) {
    private val json = Json { prettyPrint = true }
    private val groups = linkedMapOf<String, PermissionGroup>()
    private val users = linkedMapOf<String, PermissionUser>()

    init {
        if (Files.exists(file)) {
            val document = json.decodeFromString<PermissionDocument>(Files.readString(file))
            document.groups.forEach { groups[it.name] = it }
            document.users.forEach { users[it.name] = it }
        }
    }

    /**
     * Creates or replaces a group.
     *
     * @param group the group to persist.
     */
    @Synchronized
    fun saveGroup(group: PermissionGroup) {
        groups[group.name.lowercase()] = group.copy(name = group.name.lowercase())
        persist()
    }

    /**
     * Deletes a group and removes it from all members and parent lists.
     *
     * @param name the group name.
     * @return `true` if the group existed.
     */
    @Synchronized
    fun deleteGroup(name: String): Boolean {
        val key = name.lowercase()
        if (groups.remove(key) == null) {
            return false
        }
        groups.values.toList().forEach { group ->
            if (key in group.parents) {
                groups[group.name] = group.copy(parents = group.parents - key)
            }
        }
        users.values.toList().forEach { user ->
            if (key in user.groups) {
                users[user.name] = user.copy(groups = user.groups - key)
            }
        }
        persist()
        return true
    }

    /**
     * Looks up a group.
     *
     * @param name the group name.
     * @return the group or `null`.
     */
    @Synchronized
    fun group(name: String): PermissionGroup? = groups[name.lowercase()]

    /**
     * Lists all groups.
     *
     * @return groups sorted by descending weight, then name.
     */
    @Synchronized
    fun allGroups(): List<PermissionGroup> =
        groups.values.sortedWith(compareByDescending<PermissionGroup> { it.weight }.thenBy { it.name })

    /**
     * Looks up a user profile.
     *
     * @param name player name.
     * @return the profile, or an empty profile for unknown players.
     */
    @Synchronized
    fun user(name: String): PermissionUser =
        users[name.lowercase()] ?: PermissionUser(name = name.lowercase())

    /**
     * Creates or replaces a user profile; empty profiles are removed.
     *
     * @param user the profile to persist.
     */
    @Synchronized
    fun saveUser(user: PermissionUser) {
        val normalized = user.copy(name = user.name.lowercase())
        if (normalized.groups.isEmpty() && normalized.permissions.isEmpty()) {
            users.remove(normalized.name)
        } else {
            users[normalized.name] = normalized
        }
        persist()
    }

    /**
     * Resolves whether a player has a permission.
     *
     * @param player player name.
     * @param permission requested permission node.
     * @return `true` when granted.
     */
    @Synchronized
    fun has(player: String, permission: String): Boolean {
        val profile = user(player)
        PermissionMatcher.decide(profile.permissions, permission)?.let { return it }
        val memberships = profile.groups
            .mapNotNull { groups[it] }
            .ifEmpty { groups.values.filter { it.default } }
            .sortedByDescending { it.weight }
        memberships.forEach { group ->
            expand(group).forEach { level ->
                PermissionMatcher.decide(level.permissions, permission)?.let { return it }
            }
        }
        return false
    }

    /**
     * Effective group chain of a group: itself, then its parents
     * depth-first, cycles skipped.
     *
     * @param group the starting group.
     * @return the group followed by its transitive parents.
     */
    @Synchronized
    fun expand(group: PermissionGroup): List<PermissionGroup> {
        val visited = linkedSetOf<String>()
        val ordered = mutableListOf<PermissionGroup>()
        /** Depth-first parent traversal with cycle guard. */
        fun visit(current: PermissionGroup) {
            if (!visited.add(current.name)) {
                return
            }
            ordered += current
            current.parents.mapNotNull { groups[it] }.forEach(::visit)
        }
        visit(group)
        return ordered
    }

    private fun persist() {
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            json.encodeToString(PermissionDocument(groups.values.toList(), users.values.toList())),
        )
    }
}
