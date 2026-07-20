package org.helix.addons.economy

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

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
