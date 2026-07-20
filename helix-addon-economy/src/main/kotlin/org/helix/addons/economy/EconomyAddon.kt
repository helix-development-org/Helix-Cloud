package org.helix.addons.economy

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * JSON-file backed coin balances.
 *
 * @property file the `balances.json` path.
 */
class BalanceStore(private val file: Path) {
    private val json = Json { prettyPrint = true }
    private val balances = linkedMapOf<String, Long>()

    init {
        if (Files.exists(file)) {
            json.decodeFromString<Map<String, Long>>(Files.readString(file))
                .forEach { (name, amount) -> balances[name] = amount }
        }
    }

    /**
     * Reads a balance.
     *
     * @param player player name.
     * @return the balance, 0 for unknown players.
     */
    @Synchronized
    fun balance(player: String): Long = balances[player.lowercase()] ?: 0

    /**
     * Adds a (possibly negative) amount to a balance.
     *
     * @param player player name.
     * @param amount delta in coins.
     * @return the new balance.
     * @throws IllegalArgumentException if the result would be negative.
     */
    @Synchronized
    fun add(player: String, amount: Long): Long {
        val updated = balance(player) + amount
        require(updated >= 0) { "insufficient funds" }
        balances[player.lowercase()] = updated
        persist()
        return updated
    }

    /**
     * Overwrites a balance.
     *
     * @param player player name.
     * @param amount new balance, must not be negative.
     */
    @Synchronized
    fun set(player: String, amount: Long) {
        require(amount >= 0) { "balance must not be negative" }
        balances[player.lowercase()] = amount
        persist()
    }

    /**
     * Snapshot of all balances.
     *
     * @return player name to balance, highest first.
     */
    @Synchronized
    fun all(): Map<String, Long> =
        balances.entries.sortedByDescending { it.value }.associate { it.key to it.value }

    /**
     * Transfers coins between two players atomically.
     *
     * @param from paying player.
     * @param to receiving player.
     * @param amount coins to transfer, must be positive.
     * @throws IllegalArgumentException on insufficient funds or bad amount.
     */
    @Synchronized
    fun transfer(from: String, to: String, amount: Long) {
        require(amount > 0) { "amount must be positive" }
        require(balance(from) >= amount) { "insufficient funds" }
        balances[from.lowercase()] = balance(from) - amount
        balances[to.lowercase()] = balance(to) + amount
        persist()
    }

    private fun persist() {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(balances.toMap()))
    }
}

/**
 * Economy addon.
 *
 * Coin balances with the in-game `/balance` and `/pay` commands and the
 * `eco.*` admin actions. Transfer notifications run through the generic
 * `player.message` action.
 */
class EconomyAddon : AddonBase() {
    private lateinit var store: BalanceStore

    /**
     * Registers the player commands and admin actions.
     */
    override fun enable() {
        store = BalanceStore(context.dataDirectory.resolve("balances.json"))
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
            ActionResult.ok("&6Your balance: &f${store.balance(executor)} coins")
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
            ActionResult.ok(kotlinx.serialization.json.Json.encodeToString(store.all()))
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
            ?: return ActionResult.error("Usage: /pay <player> <amount>")
        val amount = invocation.arguments.getOrNull(2)?.toLongOrNull()
            ?: return ActionResult.error("Usage: /pay <player> <amount>")
        if (executor.equals(target, ignoreCase = true)) {
            return ActionResult.error("You cannot pay yourself.")
        }
        return try {
            store.transfer(executor, target, amount)
            context.actions.invoke(
                ActionInvocation(
                    "player.message",
                    listOf(target, "&6$executor sent you &f$amount coins&6."),
                    ActionSource.ADDON,
                ),
            )
            ActionResult.ok("&6You sent &f$amount coins &6to $target.")
        } catch (failure: IllegalArgumentException) {
            ActionResult.error("&c${failure.message}")
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
