package org.helix.addons.bans

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
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
        store = BanStore(context.storage())
        msg = context.messages(
            mapOf(
                "banned" to "&cYou are banned from this network.\n&7Reason: &f{reason}",
                "banned.temp" to "&cYou are banned from this network.\n&7Reason: &f{reason}\n&7Expires in &f{time}",
                "notify.set" to "&c[Ban] &f{player} &7was banned: {reason} ({expiry})",
                "notify.pardon" to "&a[Ban] &f{player} &7was pardoned.",
            ),
        )
        context.registerJoinGate { request ->
            store.activeBan(request.name)
                ?.let { JoinDecision.deny(banMessage(it)) }
                ?: JoinDecision.allow()
        }
        action(
            "ban.set",
            "Bans a player, optionally temporary (30m, 12h, 7d).",
            "ban.set <player> [duration] [reason...]",
        ) { invocation -> setBan(invocation) }
        action("ban.pardon", "Lifts a ban.", "ban.pardon <player>") { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: ban.pardon <player>")
            if (store.pardon(player)) {
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
        action("ban.export", "Exports all active bans as JSON (used by the dashboard).", "ban.export") {
            ActionResult.ok(kotlinx.serialization.json.Json.encodeToString(store.all()))
        }
        panel(
            "bans",
            "Bans",
            "/panel.html",
            "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M5.6 5.6l12.8 12.8\"/>",
        )
    }

    private fun setBan(invocation: ActionInvocation): ActionResult {
        val arguments = invocation.arguments
        val player = arguments.firstOrNull()
            ?: return ActionResult.error("usage: ban.set <player> [duration] [reason...]")
        val durationToken = arguments.getOrNull(1)?.takeIf(BanDuration::isDurationToken)
        val durationMs = durationToken?.let(BanDuration::parseMillis)
        val reason = arguments.drop(if (durationToken != null) 2 else 1)
            .joinToString(" ")
            .ifBlank { "misconduct" }
        val entry = store.set(player, reason, durationMs)
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

    private fun describe(entry: BanEntry): String =
        "${entry.player} — ${entry.reason} (${expiryText(entry)})"

    private fun expiryText(entry: BanEntry): String = entry.expiresAtEpochMs
        ?.let { "expires in ${BanDuration.format(it - System.currentTimeMillis())}" }
        ?: "permanent"

    private fun banMessage(entry: BanEntry): String = entry.expiresAtEpochMs
        ?.let { msg.format("banned.temp", "reason" to entry.reason, "time" to BanDuration.format(it - System.currentTimeMillis())) }
        ?: msg.format("banned", "reason" to entry.reason)
}
