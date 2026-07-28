package org.helix.addons.stats

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Generic per-player numeric stat storage, backed by the addon's document
 * storage.
 *
 * Stat names are free-form keys (`kills`, `playtime.minutes`, ...), not a
 * hardcoded enum, so any addon or external Paper plugin can track its own
 * leaderboards through the small [add]/[set]/[get]/[top] API without this
 * addon knowing about them. Every mutation persists the whole document.
 *
 * @property storage addon-scoped document store.
 */
class StatsStore(private val storage: AddonStorage) {
    private val json = Json { prettyPrint = true }
    private val stats = mutableMapOf<String, MutableMap<String, Long>>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            val document = json.decodeFromString<StatsDocument>(raw)
            document.stats.forEach { (stat, players) -> stats[stat] = players.toMutableMap() }
        }
    }

    /**
     * Adds a (possibly negative) delta to a player's stat.
     *
     * @param stat stat key, case-insensitive.
     * @param player player name.
     * @param delta amount to add, may be negative to decrement.
     * @return the new value.
     */
    @Synchronized
    fun add(stat: String, player: String, delta: Long): Long {
        val players = stats.getOrPut(stat.lowercase()) { mutableMapOf() }
        val updated = (players[player.lowercase()] ?: 0L) + delta
        players[player.lowercase()] = updated
        persist()
        return updated
    }

    /**
     * Overwrites a player's stat with an absolute value.
     *
     * @param stat stat key, case-insensitive.
     * @param player player name.
     * @param value new value.
     */
    @Synchronized
    fun set(stat: String, player: String, value: Long) {
        stats.getOrPut(stat.lowercase()) { mutableMapOf() }[player.lowercase()] = value
        persist()
    }

    /**
     * Reads a player's stat value.
     *
     * @param stat stat key, case-insensitive.
     * @param player player name.
     * @return the current value, `0` when unrecorded.
     */
    @Synchronized
    fun get(stat: String, player: String): Long = stats[stat.lowercase()]?.get(player.lowercase()) ?: 0L

    /**
     * Returns the top players for a stat, highest value first.
     *
     * @param stat stat key, case-insensitive.
     * @param limit maximum number of entries returned.
     * @return player name to value, sorted descending, ties broken alphabetically by player.
     */
    @Synchronized
    fun top(stat: String, limit: Int): List<Pair<String, Long>> =
        (stats[stat.lowercase()] ?: emptyMap())
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit.coerceAtLeast(0))
            .map { it.key to it.value }

    /**
     * Lists all stat keys that currently have at least one recorded value.
     *
     * @return stat keys sorted alphabetically.
     */
    @Synchronized
    fun statKeys(): List<String> = stats.keys.sorted()

    /**
     * Snapshot of a stat's current standings, used to archive a season
     * before [clear] resets it.
     *
     * @param stat stat key, case-insensitive.
     * @return player name to value, unsorted; empty when nothing is recorded.
     */
    @Synchronized
    fun snapshot(stat: String): Map<String, Long> = (stats[stat.lowercase()] ?: emptyMap()).toMap()

    /**
     * Clears all recorded values for a stat, leaving other stats untouched.
     *
     * @param stat stat key, case-insensitive.
     * @return `false` when the stat had no recorded values.
     */
    @Synchronized
    fun clear(stat: String): Boolean {
        val removed = stats.remove(stat.lowercase())
        if (removed != null) {
            persist()
        }
        return removed != null
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(StatsDocument(stats.mapValues { it.value.toMap() })))
    }

    private companion object {
        /** Document key holding all live stat values. */
        const val DOCUMENT = "stats"
    }
}
