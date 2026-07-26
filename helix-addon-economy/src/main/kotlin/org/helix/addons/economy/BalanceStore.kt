package org.helix.addons.economy

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Coin balances backed by the addon's document storage.
 *
 * Players who never interacted have no stored entry and start at
 * [startingBalance]; the first deposit/transfer/set persists a concrete
 * balance. Because every mutation reads through [balance], the starting
 * balance is applied consistently across pay, transfer and admin actions.
 *
 * @property storage addon-scoped document store.
 * @property startingBalance balance new players begin with.
 */
class BalanceStore(private val storage: AddonStorage, private val startingBalance: Long = 0) {
    private val json = Json { prettyPrint = true }
    private val balances = linkedMapOf<String, Long>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            json.decodeFromString<Map<String, Long>>(raw)
                .forEach { (name, amount) -> balances[name] = amount }
        }
    }

    /**
     * Reads a balance.
     *
     * @param player player name.
     * @return the balance, [startingBalance] for players without an entry.
     */
    @Synchronized
    fun balance(player: String): Long = balances[player.lowercase()] ?: startingBalance

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
        storage.write(DOCUMENT, json.encodeToString(balances.toMap()))
    }

    private companion object {
        /** Document key holding the balances map. */
        const val DOCUMENT = "balances"
    }
}
