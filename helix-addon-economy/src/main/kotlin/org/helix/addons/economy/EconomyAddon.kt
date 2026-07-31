package org.helix.addons.economy

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerDataProvider

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
        store = BalanceStore(context.storage(), startingBalance = STARTING_BALANCE)
        msg = loadMessages()
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
        // Expose each online player's balance as a bridge value so the sidebar
        // scoreboard's {balance} placeholder resolves without a per-player round-trip.
        context.registerPlayerListener(
            /** Publishes the joining player's balance for the sidebar `{balance}` placeholder. */
            object : org.helix.api.addon.PlayerListener {
                override fun onJoin(player: org.helix.api.player.OnlinePlayer) = publishBalance(player.name)
            },
        )
        context.registerPlayerDataProvider(
            /** Exports the player's balance; resets it to the starting balance on delete. */
            object : PlayerDataProvider {
                override fun export(player: String): String? =
                    store.all()[player.lowercase()]?.let { Json.encodeToString(mapOf("balance" to it)) }

                override fun delete(player: String): Boolean {
                    val existed = store.all().containsKey(player.lowercase())
                    if (existed) {
                        store.set(player, STARTING_BALANCE)
                        publishBalance(player)
                    }
                    return existed
                }
            },
        )
    }

    /** Publishes a player's balance as the `economy.balance.<name>` bridge value. */
    private fun publishBalance(player: String) {
        context.publishBridgeValue("economy.balance.${player.lowercase()}", store.balance(player).toString())
    }

    /**
     * Transfers coins to a target that must be a real, previously-seen
     * player — online now or resolvable through the node's identity
     * registry — so a misspelled or never-seen name errors out instead of
     * silently creating a balance record nobody will ever claim.
     */
    private fun pay(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("missing executing player")
        val target = invocation.arguments.getOrNull(1)
            ?: return ActionResult.error(msg.formatFor(executor, "error.usage"))
        val amount = invocation.arguments.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
            ?: return ActionResult.error(msg.formatFor(executor, "error.usage"))
        if (executor.equals(target, ignoreCase = true)) {
            return ActionResult.error(msg.formatFor(executor, "error.self"))
        }
        if (!isKnownPlayer(target)) {
            return ActionResult.error(msg.formatFor(executor, "error.unknown"))
        }
        return try {
            store.transfer(executor, target, amount)
            publishBalance(executor)
            publishBalance(target)
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

    /** Whether [name] is online now or has ever joined with a known uuid. */
    private fun isKnownPlayer(name: String): Boolean =
        context.onlinePlayers().any { it.name.equals(name, ignoreCase = true) } ||
            context.resolvePlayerUuid(name) != null

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
            publishBalance(player)
            ActionResult.ok("$player now has $updated coins")
        } catch (failure: IllegalArgumentException) {
            ActionResult.error(failure.message ?: "failed")
        }
    }

    private companion object {
        /** Coins every player starts with before their first transaction. */
        const val STARTING_BALANCE = 1000L
    }
}
