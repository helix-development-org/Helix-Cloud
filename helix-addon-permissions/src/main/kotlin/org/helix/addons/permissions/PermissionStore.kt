package org.helix.addons.permissions

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Permission storage (with the resolution logic) backed by the addon's
 * document storage.
 *
 * Precedence, highest first: personal user permissions, then the user's
 * groups by descending weight, each group followed by its inherited
 * parents (depth-first, cycles ignored). At every level an explicit
 * negation (`-node`) beats a grant. Players without groups belong to all
 * `default` groups. Timed grants (permissions and group memberships with an
 * expiry) count like their permanent counterparts while active and are
 * pruned once expired.
 *
 * Users are keyed on uuid once known, falling back to the lowercase name for
 * players this node has never seen join. A name-keyed profile is migrated to
 * its uuid the first time that uuid becomes resolvable — the fix for name
 * succession inheriting another player's permissions, since a freed name
 * resolves to whoever currently owns it, never the profile of whoever held
 * it before.
 *
 * @property storage addon-scoped document store.
 * @property resolveUuid resolves a player name to its current owner's uuid,
 *  typically the node's identity registry via `AddonContext.resolvePlayerUuid`.
 * @property clock epoch-millis source, injectable for tests.
 */
class PermissionStore(
    private val storage: AddonStorage,
    private val resolveUuid: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val groups = linkedMapOf<String, PermissionGroup>()
    private val users = linkedMapOf<String, PermissionUser>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            val document = json.decodeFromString<PermissionDocument>(raw)
            document.groups.forEach { groups[it.name] = it }
            document.users.forEach { users[it.uuid ?: it.name] = it }
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
        users.entries.toList().forEach { (userKey, user) ->
            if (key in user.groups) {
                users[userKey] = user.copy(groups = user.groups - key)
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
     * Looks up a user profile; expired timed grants are pruned first.
     *
     * @param name player name.
     * @param uuid the player's uuid, when known directly (for example from a
     *  join or permission check); otherwise resolved from [resolveUuid].
     * @return the profile, or an empty profile for unknown players.
     */
    @Synchronized
    fun user(name: String, uuid: String? = null): PermissionUser {
        val key = keyOf(name, uuid)
        val resolved = uuid ?: resolveUuid(name.lowercase())
        val stored = users[key] ?: return PermissionUser(name = name.lowercase(), uuid = resolved)
        val now = clock()
        val pruned = stored.copy(
            timedPermissions = stored.timedPermissions.filter { it.active(now) },
            timedGroups = stored.timedGroups.filter { it.active(now) },
        )
        if (pruned != stored) {
            if (pruned.isEmpty()) users.remove(key) else users[key] = pruned
            persist()
        }
        return pruned
    }

    /**
     * Creates or replaces a user profile; empty profiles are removed.
     *
     * @param user the profile to persist.
     */
    @Synchronized
    fun saveUser(user: PermissionUser) {
        val lower = user.name.lowercase()
        val resolved = user.uuid ?: resolveUuid(lower)
        val normalized = user.copy(name = lower, uuid = resolved)
        users.remove(lower)
        resolved?.let(users::remove)
        if (!normalized.isEmpty()) {
            users[resolved ?: lower] = normalized
        }
        persist()
    }

    /**
     * Resolves the storage key for [name] and migrates a legacy name-keyed
     * profile to its uuid the moment that uuid becomes known.
     *
     * @param name player name.
     * @param uuidHint uuid supplied directly by the caller, preferred over
     *  [resolveUuid].
     * @return the map key to use: the uuid when known, else the lowercase name.
     */
    private fun keyOf(name: String, uuidHint: String?): String {
        val lower = name.lowercase()
        val resolved = uuidHint ?: resolveUuid(lower)
        if (resolved == null) {
            return lower
        }
        val legacy = users[lower]
        if (legacy != null && legacy.uuid == null) {
            users.remove(lower)
            if (resolved !in users) {
                users[resolved] = legacy.copy(uuid = resolved)
            }
            persist()
        }
        return resolved
    }

    /**
     * Full snapshot of groups and users, for export to the dashboard.
     *
     * @return the current document.
     */
    @Synchronized
    fun document(): PermissionDocument =
        PermissionDocument(groups.values.toList(), users.values.toList())

    /**
     * Resolves whether a player has a permission.
     *
     * @param player player name.
     * @param permission requested permission node.
     * @param uuid the player's uuid, when known directly; otherwise resolved
     *  from [resolveUuid].
     * @return `true` when granted.
     */
    @Synchronized
    fun has(player: String, permission: String, uuid: String? = null): Boolean {
        val profile = user(player, uuid)
        val personal = profile.permissions + profile.timedPermissions.map { it.value }
        PermissionMatcher.decide(personal, permission)?.let { return it }
        val memberships = (profile.groups + profile.timedGroups.map { it.value })
            .distinct()
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
     * The group whose prefix/color a player displays: the player's
     * memberships (explicit and timed, falling back to the default groups)
     * by descending weight, each expanded through its parents — the first
     * group carrying a prefix or color wins. Permission nodes play no role
     * here, so a `*` grant never changes how someone is displayed.
     *
     * @param player player name.
     * @return the display group, or `null` when no group defines a prefix.
     */
    @Synchronized
    fun displayGroup(player: String): PermissionGroup? {
        val profile = user(player)
        val memberships = (profile.groups + profile.timedGroups.map { it.value })
            .distinct()
            .mapNotNull { groups[it] }
            .sortedByDescending { it.weight }
        // Default groups close the chain: a player whose groups define no prefix still displays
        // as a regular default player instead of falling back to nothing.
        val defaults = groups.values.filter { it.default }.sortedByDescending { it.weight }
        (memberships + defaults).distinctBy { it.name }.forEach { membership ->
            expand(membership).forEach { level ->
                if (level.prefix.isNotEmpty() || level.color.isNotEmpty()) {
                    return level
                }
            }
        }
        return null
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
        storage.write(DOCUMENT, json.encodeToString(PermissionDocument(groups.values.toList(), users.values.toList())))
    }

    private companion object {
        /** Document key holding groups and users. */
        const val DOCUMENT = "permissions"
    }
}
