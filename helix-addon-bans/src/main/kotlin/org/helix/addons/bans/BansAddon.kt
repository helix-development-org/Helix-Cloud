package org.helix.addons.bans

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerDataProvider
import org.helix.api.message.Messages
import org.helix.api.proxy.JoinDecision

/**
 * Network ban addon.
 *
 * Bans players by name with optional expiry (`7d`, `12h`, …). Enforcement
 * is fully generic: joins are blocked through the node's join gate and
 * online players are removed through the built-in `player.kick` action —
 * the bridges contain zero ban-specific code.
 */
class BansAddon : AddonBase() {
    private lateinit var store: BanStore
    private lateinit var msg: Messages

    /**
     * Registers the ban actions and the join gate.
     */
    override fun enable() {
        store = BanStore(context.storage(), resolveUuid = context::resolvePlayerUuid)
        msg = context.localizedMessages(
            mapOf(
                "en" to mapOf(
                    // Disconnect screens — MiniMessage, multi-line. Placeholders:
                    // {player} {reason} {remaining} {expiry} {duration} {staff} {network} {date} {time}
                    "banned" to (
                        "<red><bold>You are banned</bold>\n" +
                            "<gray>from {network}\n \n" +
                            "<gray>Reason: <white>{reason}"
                        ),
                    "banned.temp" to (
                        "<red><bold>You are temporarily banned</bold>\n" +
                            "<gray>from {network}\n \n" +
                            "<gray>Reason: <white>{reason}\n" +
                            "<gray>Time left: <yellow>{remaining}\n" +
                            "<gray>Expires: <white>{expiry}"
                        ),
                    "notify.set" to "&c[Ban] &f{player} &7was banned: {reason} ({expiry})",
                    "notify.pardon" to "&a[Ban] &f{player} &7was pardoned.",
                    "help.header" to "&cBan commands:",
                    "help.set" to "&f/bans set <player> [duration] [reason...] &7— 30m, 12h, 7d or permanent",
                    "help.pardon" to "&f/bans pardon <player>",
                    "help.check" to "&f/bans check <player>",
                    "help.list" to "&f/bans list",
                    "help.history" to "&f/bans history <player>",
                ),
                "de" to mapOf(
                    "banned" to (
                        "<red><bold>Du bist gebannt</bold>\n" +
                            "<gray>von {network}\n \n" +
                            "<gray>Grund: <white>{reason}"
                        ),
                    "banned.temp" to (
                        "<red><bold>Du bist vorübergehend gebannt</bold>\n" +
                            "<gray>von {network}\n \n" +
                            "<gray>Grund: <white>{reason}\n" +
                            "<gray>Verbleibende Zeit: <yellow>{remaining}\n" +
                            "<gray>Läuft ab: <white>{expiry}"
                        ),
                    "notify.set" to "&c[Ban] &f{player} &7wurde gebannt: {reason} ({expiry})",
                    "notify.pardon" to "&a[Ban] &f{player} &7wurde entbannt.",
                    "help.header" to "&cBan-Befehle:",
                    "help.set" to "&f/bans set <player> [duration] [reason...] &7— 30m, 12h, 7d oder permanent",
                    "help.pardon" to "&f/bans pardon <player>",
                    "help.check" to "&f/bans check <player>",
                    "help.list" to "&f/bans list",
                    "help.history" to "&f/bans history <player>",
                ),
            ),
        )
        context.registerJoinGate { request ->
            // request.uuid is the bridge-reported uuid of the actual joining account — checking
            // by it (not just the current name) is what stops a rename from evading a ban.
            store.activeBan(request.name, request.uuid)
                ?.let { JoinDecision.deny(banMessage(it)) }
                ?: JoinDecision.allow()
        }
        context.registerPlayerDataProvider(
            /** Exports/pardons the player's active ban for GDPR requests. */
            object : PlayerDataProvider {
                override fun export(player: String): String? =
                    store.activeBan(player)?.let { kotlinx.serialization.json.Json.encodeToString(it) }

                override fun delete(player: String): Boolean = store.pardon(player)
            },
        )
        action(
            "ban.set",
            "Bans a player, optionally temporary (30m, 12h, 7d).",
            "ban.set <player> <issuedBy> [duration] [reason...]",
        ) { invocation -> setBan(invocation) }
        action("ban.pardon", "Lifts a ban.", "ban.pardon <player> [issuedBy]") { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: ban.pardon <player> [issuedBy]")
            val by = invocation.arguments.getOrNull(1)
            if (store.pardon(player, by = by)) {
                context.publishNotification("moderation", msg.format("notify.pardon", "player" to player))
                ActionResult.ok("pardoned $player")
            } else {
                ActionResult.error("no ban for $player")
            }
        }
        action("ban.list", "Lists all active bans.", "ban.list") {
            val bans = store.all()
            if (bans.isEmpty()) {
                ActionResult.ok("no active bans")
            } else {
                ActionResult.ok(*bans.map(::describe).toTypedArray())
            }
        }
        action("ban.check", "Shows the active ban of a player.", "ban.check <player>") { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: ban.check <player>")
            store.activeBan(player)
                ?.let { ActionResult.ok(describe(it)) }
                ?: ActionResult.ok("$player is not banned")
        }
        action("ban.history", "Shows a player's past (expired/pardoned) bans.", "ban.history <player>") { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: ban.history <player>")
            val history = store.historyOf(player)
            if (history.isEmpty()) {
                ActionResult.ok("no ban history for $player")
            } else {
                ActionResult.ok(*history.map(::describeHistory).toTypedArray())
            }
        }
        action("ban.export", "Exports all active bans as JSON (used by the dashboard).", "ban.export") {
            ActionResult.ok(kotlinx.serialization.json.Json.encodeToString(store.all()))
        }
        action(
            "bans",
            "Manage bans in-game.",
            "bans <set|pardon|check|list|history> ...",
            playerCommand = true,
            permission = "helix.bans",
        ) { invocation ->
            val executor = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("missing executing player")
            bansCommand(executor, invocation.arguments.drop(1))
        }
        panel(
            "bans",
            "Bans",
            "/panel.html",
            "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M5.6 5.6l12.8 12.8\"/>",
        )
    }

    /**
     * Dispatches the `/bans` in-game subcommands to the `ban.*` actions.
     *
     * @param executor name of the executing player.
     * @param args arguments after the executing player name.
     * @return the command result.
     */
    private fun bansCommand(executor: String, args: List<String>): ActionResult {
        val rest = args.drop(1)
        return when (args.firstOrNull()?.lowercase()) {
            "set" -> delegate("ban.set", rest.take(1) + executor + rest.drop(1))
            "pardon" -> delegate("ban.pardon", rest.take(1) + executor)
            "check" -> delegate("ban.check", rest)
            "list" -> delegate("ban.list", emptyList())
            "history" -> delegate("ban.history", rest)
            else -> ActionResult.ok(
                msg.formatFor(executor, "help.header"),
                msg.formatFor(executor, "help.set"),
                msg.formatFor(executor, "help.pardon"),
                msg.formatFor(executor, "help.check"),
                msg.formatFor(executor, "help.list"),
                msg.formatFor(executor, "help.history"),
            )
        }
    }

    private fun delegate(action: String, arguments: List<String>): ActionResult =
        context.actions.invoke(ActionInvocation(action = action, arguments = arguments, source = ActionSource.ADDON))

    private fun setBan(invocation: ActionInvocation): ActionResult {
        val arguments = invocation.arguments
        val player = arguments.firstOrNull()
            ?: return ActionResult.error("usage: ban.set <player> <issuedBy> [duration] [reason...]")
        val issuedBy = arguments.getOrNull(1).orEmpty()
        val rest = arguments.drop(2)
        val durationToken = rest.getOrNull(0)?.takeIf(BanDuration::isDurationToken)
        val durationMs = durationToken?.let(BanDuration::parseMillis)
        val reason = rest.drop(if (durationToken != null) 1 else 0)
            .joinToString(" ")
            .ifBlank { "misconduct" }
        val entry = store.set(player, reason, durationMs, issuedBy = issuedBy)
        context.publishNotification(
            "moderation",
            msg.format("notify.set", "player" to entry.player, "reason" to reason, "expiry" to expiryText(entry)),
        )
        val kick = context.actions.invoke(
            ActionInvocation(
                action = "player.kick",
                arguments = listOf(player, banMessage(entry)),
                source = ActionSource.ADDON,
            ),
        )
        return ActionResult.ok(
            describe(entry),
            if (kick.success) kick.lines.firstOrNull() ?: "kick queued" else "not kicked: player offline or no proxy",
        )
    }

    private fun describe(entry: BanEntry): String {
        val by = entry.issuedBy.ifBlank { "unknown" }
        return "${entry.player} — ${entry.reason} (${expiryText(entry)}) — by $by"
    }

    private fun describeHistory(entry: BanEntry): String {
        val by = entry.issuedBy.ifBlank { "unknown" }
        val lifted = entry.revokedBy?.let { "pardoned by $it" } ?: "expired"
        return "${entry.player} — ${entry.reason} — issued by $by, $lifted"
    }

    private fun expiryText(entry: BanEntry): String = entry.expiresAtEpochMs
        ?.let { "expires in ${BanDuration.format(it - System.currentTimeMillis())}" }
        ?: "permanent"

    private fun banMessage(entry: BanEntry): String {
        val expiresAt = entry.expiresAtEpochMs
        return if (expiresAt != null) {
            msg.formatFor(
                entry.player,
                "banned.temp",
                "player" to entry.player,
                "reason" to entry.reason,
                "remaining" to BanDuration.format(expiresAt - System.currentTimeMillis()),
                "time" to BanDuration.format(expiresAt - System.currentTimeMillis()),
                "duration" to BanDuration.format(expiresAt - entry.createdAtEpochMs),
                "expiry" to formatDate(expiresAt),
            )
        } else {
            msg.formatFor(
                entry.player,
                "banned",
                "player" to entry.player,
                "reason" to entry.reason,
                "duration" to "permanent",
                "expiry" to "never",
            )
        }
    }

    private fun formatDate(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
