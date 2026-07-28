package org.helix.addons.permissions

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Permission system addon.
 *
 * Groups with weight, inheritance and default flag, per-user permissions,
 * wildcard (`*`, `prefix.*`) and negation (`-node`) support. Registers a
 * permission resolver so bridges (for example the maintenance bypass) and
 * other addons can ask the node — the platform stays permission-agnostic.
 * Groups also own the display prefix/color (`perm.group.prefix`): the
 * player's highest-weight group with a prefix decides how they appear in
 * chat, tab list and the name tag.
 */
class PermissionsAddon : AddonBase() {
    /** Export JSON with every field present, so panel code needs no guards. */
    private val json = Json { encodeDefaults = true }
    private lateinit var store: PermissionStore
    private lateinit var catalog: PermissionCatalog

    /**
     * Registers the resolver and all `perm.*` actions.
     */
    override fun enable() {
        store = PermissionStore(context.storage(), resolveUuid = context::resolvePlayerUuid)
        catalog = PermissionCatalog(context)
        if (store.group("default") == null) {
            store.saveGroup(PermissionGroup(name = "default", default = true))
        }
        context.registerPermissionResolver { request -> store.has(request.name, request.permission, request.uuid) }
        // Group prefixes are DISPLAY state, deliberately decoupled from permission nodes: the
        // player's highest-weight group with a prefix wins, so a `*` grant never changes looks.
        context.registerDisplayResolver { name ->
            store.displayGroup(name)?.let { group ->
                org.helix.api.display.DisplayProfile(prefix = group.prefix, color = group.color)
            }
        }

        action(
            "perm.group.create",
            "Creates a permission group.",
            "perm.group.create <group> [weight=0] [default=false]",
        ) { invocation -> createGroup(invocation) }
        action("perm.group.delete", "Deletes a group.", "perm.group.delete <group>") { invocation ->
            val name = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: perm.group.delete <group>")
            if (store.deleteGroup(name)) ActionResult.ok("deleted group $name") else ActionResult.error("unknown group: $name")
        }
        action("perm.group.list", "Lists all groups.", "perm.group.list") {
            val groups = store.allGroups()
            if (groups.isEmpty()) {
                ActionResult.ok("no groups")
            } else {
                ActionResult.ok(
                    *groups.map { group ->
                        "${group.name} weight=${group.weight} default=${group.default} " +
                            "permissions=${group.permissions.size} parents=${group.parents.joinToString().ifEmpty { "-" }}"
                    }.toTypedArray(),
                )
            }
        }
        action("perm.group.info", "Shows a group in detail.", "perm.group.info <group>") { invocation ->
            val group = invocation.arguments.firstOrNull()?.let(store::group)
                ?: return@action ActionResult.error("unknown group: ${invocation.arguments.firstOrNull()}")
            ActionResult.ok(
                "name: ${group.name}",
                "weight: ${group.weight}, default: ${group.default}",
                "prefix: '${group.prefix}'${if (group.color.isNotEmpty()) ", color: '${group.color}'" else ""}",
                "parents: ${group.parents.joinToString().ifEmpty { "-" }}",
                "permissions: ${group.permissions.joinToString().ifEmpty { "-" }}",
            )
        }
        action(
            "perm.group.prefix",
            "Sets a group's display prefix (chat/tab/name tag); no prefix clears it.",
            "perm.group.prefix <group> [prefix...]",
        ) { invocation ->
            val group = invocation.arguments.firstOrNull()?.let(store::group)
                ?: return@action ActionResult.error("unknown group: ${invocation.arguments.firstOrNull()}")
            val raw = invocation.arguments.drop(1).joinToString(" ")
            // A trailing space separates the prefix from the name (unless the operator styles it).
            val prefix = if (raw.isEmpty() || raw.endsWith(" ")) raw else "$raw "
            store.saveGroup(group.copy(prefix = prefix))
            if (prefix.isEmpty()) {
                ActionResult.ok("prefix of ${group.name} cleared")
            } else {
                ActionResult.ok("prefix of ${group.name} set to '$prefix'")
            }
        }
        action(
            "perm.group.color",
            "Sets a group's display name color (for example &c); no color clears it.",
            "perm.group.color <group> [&color]",
        ) { invocation ->
            val group = invocation.arguments.firstOrNull()?.let(store::group)
                ?: return@action ActionResult.error("unknown group: ${invocation.arguments.firstOrNull()}")
            val color = invocation.arguments.getOrNull(1).orEmpty()
            store.saveGroup(group.copy(color = color))
            if (color.isEmpty()) {
                ActionResult.ok("color of ${group.name} cleared")
            } else {
                ActionResult.ok("color of ${group.name} set to '$color'")
            }
        }
        action("perm.group.grant", "Adds a permission to a group.", "perm.group.grant <group> <permission>") {
            groupPermission(it, add = true)
        }
        action("perm.group.revoke", "Removes a permission from a group.", "perm.group.revoke <group> <permission>") {
            groupPermission(it, add = false)
        }
        action("perm.group.addparent", "Adds an inherited group.", "perm.group.addparent <group> <parent>") {
            groupParent(it, add = true)
        }
        action("perm.group.removeparent", "Removes an inherited group.", "perm.group.removeparent <group> <parent>") {
            groupParent(it, add = false)
        }
        action("perm.user.info", "Shows a player's profile.", "perm.user.info <player>") { invocation ->
            val name = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: perm.user.info <player>")
            val user = store.user(name)
            val groups = user.groups.ifEmpty {
                store.allGroups().filter { it.default }.map { "${it.name} (default)" }
            }
            val now = System.currentTimeMillis()
            ActionResult.ok(
                "player: ${user.name}",
                "groups: ${groups.joinToString().ifEmpty { "-" }}",
                "permissions: ${user.permissions.joinToString().ifEmpty { "-" }}",
                "timed groups: ${user.timedGroups.joinToString { timed(it, now) }.ifEmpty { "-" }}",
                "timed permissions: ${user.timedPermissions.joinToString { timed(it, now) }.ifEmpty { "-" }}",
            )
        }
        action(
            "perm.user.addgroup",
            "Adds a player to a group, optionally temporary (30m, 12h, 7d).",
            "perm.user.addgroup <player> <group> [duration]",
        ) {
            userGroup(it, add = true)
        }
        action("perm.user.removegroup", "Removes a player from a group.", "perm.user.removegroup <player> <group>") {
            userGroup(it, add = false)
        }
        action(
            "perm.user.grant",
            "Grants a personal permission, optionally temporary (30m, 12h, 7d).",
            "perm.user.grant <player> <permission> [duration]",
        ) {
            userPermission(it, add = true)
        }
        action("perm.user.revoke", "Revokes a personal permission.", "perm.user.revoke <player> <permission>") {
            userPermission(it, add = false)
        }
        action(
            "perm.catalog",
            "Exports all known permission nodes (core, addons, plugin.yml) as JSON.",
            "perm.catalog",
        ) {
            ActionResult.ok(json.encodeToString(catalog.entries()))
        }
        action("perm.check", "Checks whether a player has a permission.", "perm.check <player> <permission>") { invocation ->
            val (player, permission) = twoArguments(invocation)
                ?: return@action ActionResult.error("usage: perm.check <player> <permission>")
            val granted = store.has(player, permission)
            ActionResult.ok("$player ${if (granted) "HAS" else "does NOT have"} $permission")
        }
        action("perm.export", "Exports all groups and users as JSON (used by the dashboard).", "perm.export") {
            ActionResult.ok(json.encodeToString(store.document()))
        }
        action(
            "permissions",
            "Manage permissions in-game.",
            "permissions <group|user|check> ...",
            playerCommand = true,
            permission = "helix.permissions",
        ) { invocation -> permissionsCommand(invocation.arguments.drop(1)) }
        panel("permissions", "Permissions", "/panel.html", PANEL_ICON)
    }

    private companion object {
        /** Sidebar icon for the permissions panel. */
        const val PANEL_ICON = "<path d=\"M9 12l2 2 4-4\"/><path d=\"M12 3l7 4v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V7z\"/>"
    }

    /**
     * Dispatches the `/permissions` in-game subcommands to the `perm.*` actions.
     *
     * @param args arguments after the executing player name.
     * @return the command result.
     */
    private fun permissionsCommand(args: List<String>): ActionResult = when (args.firstOrNull()?.lowercase()) {
        "group" -> args.getOrNull(1)?.let { sub -> delegate("perm.group.${sub.lowercase()}", args.drop(2)) }
            ?: ActionResult.error(
                "usage: /permissions group <create|delete|list|info|grant|revoke|addparent|removeparent> ...",
            )
        "user" -> args.getOrNull(1)?.let { sub -> delegate("perm.user.${sub.lowercase()}", args.drop(2)) }
            ?: ActionResult.error("usage: /permissions user <info|addgroup|removegroup|grant|revoke> ...")
        "check" -> delegate("perm.check", args.drop(1))
        else -> ActionResult.ok(
            "&bPermission commands:",
            "&f/permissions group <create|delete|list|info|grant|revoke|addparent|removeparent> ...",
            "&f/permissions user <info|addgroup|removegroup|grant|revoke> ...",
            "&f/permissions check <player> <permission>",
        )
    }

    private fun delegate(action: String, arguments: List<String>): ActionResult =
        context.actions.invoke(ActionInvocation(action = action, arguments = arguments, source = ActionSource.ADDON))

    private fun createGroup(invocation: ActionInvocation): ActionResult {
        val name = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("usage: perm.group.create <group> [weight=0] [default=false]")
        if (store.group(name) != null) {
            return ActionResult.error("group already exists: $name")
        }
        val options = invocation.arguments.drop(1).mapNotNull { option ->
            val parts = option.split("=", limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1] else null
        }.toMap()
        val group = PermissionGroup(
            name = name.lowercase(),
            weight = options["weight"]?.toInt() ?: 0,
            default = options["default"]?.toBooleanStrict() ?: false,
        )
        store.saveGroup(group)
        return ActionResult.ok("created group ${group.name} (weight=${group.weight}, default=${group.default})")
    }

    private fun groupPermission(invocation: ActionInvocation, add: Boolean): ActionResult {
        val (name, permission) = twoArguments(invocation)
            ?: return ActionResult.error("usage: <group> <permission>")
        val group = store.group(name) ?: return ActionResult.error("unknown group: $name")
        val updated = if (add) (group.permissions + permission).distinct() else group.permissions - permission
        store.saveGroup(group.copy(permissions = updated))
        return ActionResult.ok("${if (add) "granted" else "revoked"} $permission ${if (add) "to" else "from"} ${group.name}")
    }

    private fun groupParent(invocation: ActionInvocation, add: Boolean): ActionResult {
        val (name, parentName) = twoArguments(invocation)
            ?: return ActionResult.error("usage: <group> <parent>")
        val group = store.group(name) ?: return ActionResult.error("unknown group: $name")
        val parent = store.group(parentName) ?: return ActionResult.error("unknown group: $parentName")
        if (add && parent.name == group.name) {
            return ActionResult.error("a group cannot inherit itself")
        }
        val updated = if (add) (group.parents + parent.name).distinct() else group.parents - parent.name
        store.saveGroup(group.copy(parents = updated))
        return ActionResult.ok("${group.name} parents: ${updated.joinToString().ifEmpty { "-" }}")
    }

    private fun userGroup(invocation: ActionInvocation, add: Boolean): ActionResult {
        val (player, groupName) = twoArguments(invocation)
            ?: return ActionResult.error("usage: <player> <group> [duration]")
        val group = store.group(groupName) ?: return ActionResult.error("unknown group: $groupName")
        val user = store.user(player)
        if (!add) {
            store.saveUser(
                user.copy(
                    groups = user.groups - group.name,
                    timedGroups = user.timedGroups.filter { it.value != group.name },
                ),
            )
            return ActionResult.ok("removed ${user.name} from ${group.name}")
        }
        val expiry = expiryOf(invocation.arguments.getOrNull(2))
            ?: return ActionResult.error("invalid duration: ${invocation.arguments.getOrNull(2)}")
        return if (expiry > 0) {
            store.saveUser(
                user.copy(
                    timedGroups = user.timedGroups.filter { it.value != group.name } +
                        TimedGrant(group.name, expiry),
                ),
            )
            ActionResult.ok("${user.name} joined ${group.name} until ${GrantDuration.format(expiry - System.currentTimeMillis())} from now")
        } else {
            store.saveUser(user.copy(groups = (user.groups + group.name).distinct()))
            ActionResult.ok("${user.name} groups: ${(user.groups + group.name).distinct().joinToString()}")
        }
    }

    private fun userPermission(invocation: ActionInvocation, add: Boolean): ActionResult {
        val (player, permission) = twoArguments(invocation)
            ?: return ActionResult.error("usage: <player> <permission> [duration]")
        val user = store.user(player)
        if (!add) {
            store.saveUser(
                user.copy(
                    permissions = user.permissions - permission,
                    timedPermissions = user.timedPermissions.filter { !it.value.equals(permission, ignoreCase = true) },
                ),
            )
            return ActionResult.ok("revoked $permission from ${user.name}")
        }
        val expiry = expiryOf(invocation.arguments.getOrNull(2))
            ?: return ActionResult.error("invalid duration: ${invocation.arguments.getOrNull(2)}")
        return if (expiry > 0) {
            store.saveUser(
                user.copy(
                    timedPermissions = user.timedPermissions.filter { !it.value.equals(permission, ignoreCase = true) } +
                        TimedGrant(permission, expiry),
                ),
            )
            ActionResult.ok("granted $permission to ${user.name} for ${GrantDuration.format(expiry - System.currentTimeMillis())}")
        } else {
            store.saveUser(user.copy(permissions = (user.permissions + permission).distinct()))
            ActionResult.ok("${user.name} permissions: ${(user.permissions + permission).distinct().joinToString()}")
        }
    }

    /**
     * Resolves an optional duration token to an absolute expiry.
     *
     * @param token the duration argument, or `null`/blank for permanent.
     * @return epoch millis of expiry, `0` for permanent, `null` for invalid.
     */
    private fun expiryOf(token: String?): Long? {
        if (token.isNullOrBlank()) {
            return 0
        }
        val millis = GrantDuration.parseMillis(token) ?: return null
        return System.currentTimeMillis() + millis
    }

    private fun timed(grant: TimedGrant, nowEpochMs: Long): String =
        "${grant.value} (${GrantDuration.format(grant.expiresAtEpochMs - nowEpochMs)})"

    private fun twoArguments(invocation: ActionInvocation): Pair<String, String>? {
        val first = invocation.arguments.getOrNull(0) ?: return null
        val second = invocation.arguments.getOrNull(1) ?: return null
        return first to second
    }
}
