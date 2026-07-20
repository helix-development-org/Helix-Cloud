package org.helix.addons.permissions

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult

/**
 * Permission system addon.
 *
 * Groups with weight, inheritance and default flag, per-user permissions,
 * wildcard (`*`, `prefix.*`) and negation (`-node`) support. Registers a
 * permission resolver so bridges (for example the maintenance bypass) and
 * other addons can ask the node — the platform stays permission-agnostic.
 */
class PermissionsAddon : AddonBase() {
    private lateinit var store: PermissionStore

    /**
     * Registers the resolver and all `perm.*` actions.
     */
    override fun enable() {
        store = PermissionStore(context.dataDirectory.resolve("permissions.json"))
        if (store.group("default") == null) {
            store.saveGroup(PermissionGroup(name = "default", default = true))
        }
        context.registerPermissionResolver { request -> store.has(request.name, request.permission) }

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
                "parents: ${group.parents.joinToString().ifEmpty { "-" }}",
                "permissions: ${group.permissions.joinToString().ifEmpty { "-" }}",
            )
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
            ActionResult.ok(
                "player: ${user.name}",
                "groups: ${groups.joinToString().ifEmpty { "-" }}",
                "permissions: ${user.permissions.joinToString().ifEmpty { "-" }}",
            )
        }
        action("perm.user.addgroup", "Adds a player to a group.", "perm.user.addgroup <player> <group>") {
            userGroup(it, add = true)
        }
        action("perm.user.removegroup", "Removes a player from a group.", "perm.user.removegroup <player> <group>") {
            userGroup(it, add = false)
        }
        action("perm.user.grant", "Grants a personal permission.", "perm.user.grant <player> <permission>") {
            userPermission(it, add = true)
        }
        action("perm.user.revoke", "Revokes a personal permission.", "perm.user.revoke <player> <permission>") {
            userPermission(it, add = false)
        }
        action("perm.check", "Checks whether a player has a permission.", "perm.check <player> <permission>") { invocation ->
            val (player, permission) = twoArguments(invocation)
                ?: return@action ActionResult.error("usage: perm.check <player> <permission>")
            val granted = store.has(player, permission)
            ActionResult.ok("$player ${if (granted) "HAS" else "does NOT have"} $permission")
        }
        action("perm.export", "Exports all groups and users as JSON (used by the dashboard).", "perm.export") {
            ActionResult.ok(Json.encodeToString(store.document()))
        }
        panel("permissions", "Permissions", "/panel.html", PANEL_ICON)
    }

    private companion object {
        /** Sidebar icon for the permissions panel. */
        const val PANEL_ICON = "<path d=\"M9 12l2 2 4-4\"/><path d=\"M12 3l7 4v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V7z\"/>"
    }

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
            ?: return ActionResult.error("usage: <player> <group>")
        val group = store.group(groupName) ?: return ActionResult.error("unknown group: $groupName")
        val user = store.user(player)
        val updated = if (add) (user.groups + group.name).distinct() else user.groups - group.name
        store.saveUser(user.copy(groups = updated))
        return ActionResult.ok("${user.name} groups: ${updated.joinToString().ifEmpty { "- (defaults apply)" }}")
    }

    private fun userPermission(invocation: ActionInvocation, add: Boolean): ActionResult {
        val (player, permission) = twoArguments(invocation)
            ?: return ActionResult.error("usage: <player> <permission>")
        val user = store.user(player)
        val updated = if (add) (user.permissions + permission).distinct() else user.permissions - permission
        store.saveUser(user.copy(permissions = updated))
        return ActionResult.ok("${user.name} permissions: ${updated.joinToString().ifEmpty { "-" }}")
    }

    private fun twoArguments(invocation: ActionInvocation): Pair<String, String>? {
        val first = invocation.arguments.getOrNull(0) ?: return null
        val second = invocation.arguments.getOrNull(1) ?: return null
        return first to second
    }
}
