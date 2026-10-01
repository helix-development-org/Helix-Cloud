package org.helix.addons.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Helix Admin addon – node side of the in-game admin panel.
 *
 * The Fabric client mod (`helix-admin-mod/`) opens the panel with Right Shift: the player's Paper server
 * (component `helix-addon-admin-paper`) obtains a one-time login ticket (`POST /internal/panel-ticket`) and
 * the mod loads the dashboard's compact in-game menu with it; "open dashboard" switches to the full
 * dashboard in the same browser.
 *
 * This addon provides what the menu needs beyond the core:
 *  - the externally reachable panel URL (`admin.panelurl`, published as bridge value `admin.panel_url`),
 *  - network-wide presence (which service every player is on, with position/health/ping), reported by the
 *    Paper components (`admin.presence`),
 *  - a job queue so in-game actions reach the service hosting the target player (`admin.job`, `admin.jobs`),
 *  - permission-gated moderation and server actions for staff sessions (`admin.kick|warn|ban|message|
 *    announce|services|service.restart`), attributed to the signed-in session, never to a caller-supplied name.
 *
 * Permissions use the `helix.staff.*` prefix – not `helix.admin.*`, whose wildcard would also grant the
 * node's full-admin node `helix.admin`.
 */
class AdminAddon : AddonBase() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val presence = Presence()
    private val jobs = JobQueue()

    /** Registers the actions and publishes the panel URL. */
    override fun enable() {
        publishPanelUrl()
        registerPanelUrl()
        registerMachineApi()
        registerStaffActions()
        registerStaffRelay()
    }

    private fun registerPanelUrl() {
        action(
            "admin.panelurl",
            "Shows or sets the externally reachable panel address the client mod opens ('-' clears it). Host alone is enough – http without a port gets the node's control port.",
            "admin.panelurl [host|url|-]",
        ) { inv ->
            val value = inv.arguments.firstOrNull()?.trim()
            when {
                value == null -> ActionResult.ok(panelUrl() ?: "(not set – clients use the server address + control port)")
                value == "-" -> {
                    context.storage().delete(PANEL_URL_KEY)
                    publishPanelUrl()
                    ActionResult.ok("panel URL cleared")
                }
                "://" in value && !value.startsWith("http://") && !value.startsWith("https://") ->
                    ActionResult.error("only http:// or https:// (or just the host)")
                else -> {
                    context.storage().write(PANEL_URL_KEY, value.trimEnd('/'))
                    publishPanelUrl()
                    ActionResult.ok("panel URL set to ${value.trimEnd('/')}")
                }
            }
        }
    }

    private fun registerMachineApi() {
        action(
            "admin.presence",
            "Machine API: a Paper service reports its online players (JSON PresenceReport).",
            "admin.presence <json…>",
            bridgeInvocable = true,
        ) { inv ->
            val report = runCatching { json.decodeFromString(PresenceReport.serializer(), inv.arguments.joinToString(" ")) }
                .getOrElse { return@action ActionResult.error("invalid presence json: ${it.message}") }
            presence.update(report)
            ActionResult.ok("ok")
        }
        action(
            "admin.where",
            "Machine API: the service a player is on.",
            "admin.where <player>",
            bridgeInvocable = true,
        ) { inv ->
            val name = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: admin.where <player>")
            presence.serviceOf(name)?.let { ActionResult.ok(it) } ?: ActionResult.error("$name is not online")
        }
        action(
            "admin.job",
            "Machine API: queues an in-game job for the service hosting a player (or an explicit service).",
            "admin.job <player|@service> <type> <json…>",
            bridgeInvocable = true,
        ) { inv ->
            val target = inv.arguments.getOrNull(0)
            val type = inv.arguments.getOrNull(1)
            if (target == null || type == null) return@action ActionResult.error("usage: admin.job <player|@service> <type> <json…>")
            val service = if (target.startsWith("@")) target.drop(1) else presence.serviceOf(target)
                ?: return@action ActionResult.error("$target is not online")
            val payload = runCatching { json.decodeFromString(JsonObject.serializer(), inv.arguments.drop(2).joinToString(" ").ifBlank { "{}" }) }
                .getOrElse { return@action ActionResult.error("invalid job json: ${it.message}") }
            val job = jobs.push(service, type, payload)
            ActionResult.ok(json.encodeToString(AdminJob.serializer(), job))
        }
        action(
            "admin.jobs",
            "Machine API: jobs for a service newer than the cursor (-1 = only fetch the cursor).",
            "admin.jobs <service> <after>",
            bridgeInvocable = true,
        ) { inv ->
            val service = inv.arguments.getOrNull(0) ?: return@action ActionResult.error("usage: admin.jobs <service> <after>")
            val after = inv.arguments.getOrNull(1)?.toLongOrNull() ?: -1
            ActionResult.ok(json.encodeToString(JobBatch.serializer(), jobs.poll(service, after)))
        }
    }

    private fun registerStaffActions() {
        action("admin.players", "Online players network-wide with service, position, health and ping (JSON).", "admin.players", permission = "helix.staff.menu") {
            ActionResult.ok(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AdminPlayer.serializer()), presence.players()))
        }
        action("admin.servers", "Reporting Paper services with TPS and player count (JSON).", "admin.servers", permission = "helix.staff.menu") {
            val list = presence.services().map { mapOf("service" to it.service, "tps" to "%.1f".format(it.tps), "players" to it.players.size.toString()) }
            ActionResult.ok(json.encodeToString(list))
        }
        action("admin.kick", "Kicks a player (staff).", "admin.kick <player> [reason…]", permission = "helix.staff.kick") { inv ->
            val player = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: admin.kick <player> [reason…]")
            val reason = inv.arguments.drop(1).joinToString(" ").ifBlank { "Kicked by ${actor(inv)}" }
            forward("player.kick", listOf(player, reason), inv)
        }
        action("admin.warn", "Warns a player via the moderation addon (staff).", "admin.warn <player> <reason…>", permission = "helix.staff.warn") { inv ->
            val player = inv.arguments.firstOrNull()
            val reason = inv.arguments.drop(1).joinToString(" ")
            if (player == null || reason.isBlank()) return@action ActionResult.error("usage: admin.warn <player> <reason…>")
            forward("warn", listOf(actor(inv), player, reason), inv)
        }
        action("admin.ban", "Bans a player via the bans addon; duration like 1h/7d or 'perm' (staff).", "admin.ban <player> <duration|perm> [reason…]", permission = "helix.staff.ban") { inv ->
            val player = inv.arguments.getOrNull(0)
            val duration = inv.arguments.getOrNull(1)
            if (player == null || duration == null) return@action ActionResult.error("usage: admin.ban <player> <duration|perm> [reason…]")
            val reason = inv.arguments.drop(2)
            val args = buildList {
                add(player)
                add(actor(inv))
                if (duration != "perm") add(duration)
                addAll(reason)
            }
            forward("ban.set", args, inv)
        }
        action("admin.message", "Sends a private message to a player (staff).", "admin.message <player> <text…>", permission = "helix.staff.message") { inv ->
            val player = inv.arguments.firstOrNull()
            val text = inv.arguments.drop(1).joinToString(" ")
            if (player == null || text.isBlank()) return@action ActionResult.error("usage: admin.message <player> <text…>")
            forward("player.message", listOf(player, "[${actor(inv)}] $text"), inv)
        }
        action("admin.announce", "Broadcasts an announcement network-wide (staff).", "admin.announce <text…>", permission = "helix.staff.announce") { inv ->
            val text = inv.arguments.joinToString(" ")
            if (text.isBlank()) return@action ActionResult.error("usage: admin.announce <text…>")
            forward("player.broadcast", listOf(text), inv)
        }
        action("admin.services", "Lists services (staff).", "admin.services", permission = "helix.staff.server") { inv ->
            forward("service.list", emptyList(), inv)
        }
        action("admin.service.restart", "Restarts a service after a short countdown (staff).", "admin.service.restart <service>", permission = "helix.staff.server") { inv ->
            val service = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: admin.service.restart <service>")
            forward("service.restart", listOf(service, "10"), inv)
        }
    }

    /**
     * Relay for native staff clients (Helix LabyMod addon via the Paper component): runs one of the staff actions
     * in the name of an in-game staff member – only if that player holds the action's own permission.
     */
    private fun registerStaffRelay() {
        action(
            "admin.staff",
            "Runs a staff action for an in-game staff member (Paper component, native panels).",
            "admin.staff <player> <admin.action> [args…]",
            bridgeInvocable = true,
        ) { inv ->
            val actor = inv.arguments.getOrNull(0)
            val target = inv.arguments.getOrNull(1)
            if (actor == null || target == null) return@action ActionResult.error("usage: admin.staff <player> <admin.action> [args…]")
            val permission = STAFF_RELAY[target] ?: return@action ActionResult.error("not relayable: $target")
            if (!context.hasPermission(actor, permission)) return@action ActionResult.error("missing permission $permission")
            context.actions.invoke(ActionInvocation(target, inv.arguments.drop(2), ActionSource.ADDON, actor = actor))
        }
    }

    /** The signed-in caller (REST session name); console/system calls are attributed to "console". */
    private fun actor(invocation: ActionInvocation): String = invocation.actor?.takeIf { it.isNotBlank() } ?: "console"

    private fun forward(action: String, arguments: List<String>, invocation: ActionInvocation): ActionResult =
        context.actions.invoke(ActionInvocation(action, arguments, ActionSource.ADDON, actor = actor(invocation)))

    private fun panelUrl(): String? = context.storage().read(PANEL_URL_KEY)?.takeIf { it.isNotBlank() }

    private fun publishPanelUrl() {
        val url = panelUrl()
        if (url == null) context.unpublishBridgeValue(PANEL_URL_BRIDGE_KEY) else context.publishBridgeValue(PANEL_URL_BRIDGE_KEY, url)
    }

    private companion object {
        /** Staff actions native panels may run through `admin.staff`, with the permission each needs. */
        val STAFF_RELAY = mapOf(
            "admin.players" to "helix.staff.menu",
            "admin.servers" to "helix.staff.menu",
            "admin.kick" to "helix.staff.kick",
            "admin.warn" to "helix.staff.warn",
            "admin.ban" to "helix.staff.ban",
            "admin.message" to "helix.staff.message",
            "admin.announce" to "helix.staff.announce",
            "admin.service.restart" to "helix.staff.server",
        )

        const val PANEL_URL_KEY = "panelUrl"
        const val PANEL_URL_BRIDGE_KEY = "admin.panel_url"
    }
}
