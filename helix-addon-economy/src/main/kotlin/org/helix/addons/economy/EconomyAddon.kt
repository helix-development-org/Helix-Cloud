package org.helix.addons.economy

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Economy addon.
 *
 * Coin balances with the in-game `/balance` and `/pay` commands and the
 * `eco.*` admin actions. Transfer notifications run through the generic
 * `player.message` action.
 */
class EconomyAddon : AddonBase() {
    private lateinit var store: BalanceStore
    private lateinit var msg: org.helix.api.message.Messages

    /**
     * Registers the player commands and admin actions.
     */
    override fun enable() {
        store = BalanceStore(context.storage())
        msg = context.localizedMessages(
            mapOf(
                "en" to mapOf(
                    "balance" to "&6Your balance: &f{balance} coins",
                    "pay.sent" to "&6You sent &f{amount} coins &6to {target}.",
                    "pay.received" to "&6{sender} sent you &f{amount} coins&6.",
                    "error.self" to "&cYou cannot pay yourself.",
                    "error.funds" to "&cYou do not have enough coins.",
                    "error.usage" to "&cUsage: /pay <player> <amount>",
                ),
                "de" to mapOf(
                    "balance" to "&6Dein Kontostand: &f{balance} Coins",
                    "pay.sent" to "&6Du hast &f{amount} Coins &6an {target} gesendet.",
                    "pay.received" to "&6{sender} hat dir &f{amount} Coins &6gesendet.",
                    "error.self" to "&cDu kannst dir nicht selbst Coins senden.",
                    "error.funds" to "&cDu hast nicht genug Coins.",
                    "error.usage" to "&cBenutzung: /pay <player> <amount>",
                ),
            ),
        )
        context.registerAction(
            ActionDescriptor(
                name = "balance",
                description = "Shows your coin balance.",
                usage = "balance",
                playerCommand = true,
            ),
        ) { invocation ->
            val executor = invocation.arguments.firstOrNull()
                ?: return@registerAction ActionResult.error("missing executing player")
            ActionResult.ok(msg.formatFor(executor, "balance", "balance" to store.balance(executor).toString()))
        }
        context.registerAction(
            ActionDescriptor(
                name = "pay",
                description = "Transfers coins to another player.",
                usage = "pay <player> <amount>",
                playerCommand = true,
            ),
        ) { invocation -> pay(invocation) }
        action("eco.get", "Shows a player's balance.", "eco.get <player>") { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: eco.get <player>")
            ActionResult.ok("$player has ${store.balance(player)} coins")
        }
        action("eco.give", "Adds coins to a player.", "eco.give <player> <amount>") { invocation ->
            adminChange(invocation) { player, amount -> store.add(player, amount) }
        }
        action("eco.take", "Removes coins from a player.", "eco.take <player> <amount>") { invocation ->
            adminChange(invocation) { player, amount -> store.add(player, -amount) }
        }
        action("eco.set", "Sets a player's balance.", "eco.set <player> <amount>") { invocation ->
            adminChange(invocation) { player, amount ->
                store.set(player, amount)
                amount
            }
        }
        action("eco.export", "Exports all balances as JSON (used by the dashboard).", "eco.export") {
            ActionResult.ok(Json.encodeToString(store.all()))
        }
        panel(
            "economy",
            "Economy",
            "/panel.html",
            "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M14.5 9a2.5 2.5 0 00-2.5-1.5c-1.4 0-2.5.8-2.5 2s1.1 1.8 2.5 2 2.5.8 2.5 2-1.1 2-2.5 2A2.5 2.5 0 019.5 15M12 6v1.5M12 16.5V18\"/>",
        )
    }

    private fun pay(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("missing executing player")
        val target = invocation.arguments.getOrNull(1)
            ?: return ActionResult.error(msg.formatFor(executor, "error.usage"))
        val amount = invocation.arguments.getOrNull(2)?.toLongOrNull()
            ?: return ActionResult.error(msg.formatFor(executor, "error.usage"))
        if (executor.equals(target, ignoreCase = true)) {
            return ActionResult.error(msg.formatFor(executor, "error.self"))
        }
        return try {
            store.transfer(executor, target, amount)
            context.actions.invoke(
                ActionInvocation(
                    "player.message",
                    listOf(
                        target,
                        msg.formatFor(target, "pay.received", "sender" to executor, "amount" to amount.toString()),
                    ),
                    ActionSource.ADDON,
                ),
            )
            ActionResult.ok(msg.formatFor(executor, "pay.sent", "amount" to amount.toString(), "target" to target))
        } catch (failure: IllegalArgumentException) {
            ActionResult.error(msg.formatFor(executor, "error.funds"))
        }
    }

    private fun adminChange(
        invocation: ActionInvocation,
        change: (String, Long) -> Long,
    ): ActionResult {
        val player = invocation.arguments.getOrNull(0)
        val amount = invocation.arguments.getOrNull(1)?.toLongOrNull()
        if (player == null || amount == null || amount < 0) {
            return ActionResult.error("usage: <player> <amount>")
        }
        return try {
            val updated = change(player, amount)
            ActionResult.ok("$player now has $updated coins")
        } catch (failure: IllegalArgumentException) {
            ActionResult.error(failure.message ?: "failed")
        }
    }
}
