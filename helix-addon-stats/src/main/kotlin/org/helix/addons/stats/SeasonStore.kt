package org.helix.addons.stats

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Seasonal archive for [StatsStore] leaderboards.
 *
 * A reset never silently destroys data: [reset] snapshots the current
 * standings into a new [SeasonRecord] and persists it *before* clearing the
 * live stat through [StatsStore.clear]. Past seasons stay viewable through
 * [seasons] and [season] after the live leaderboard has restarted at zero.
 *
 * @property storage addon-scoped document store.
 * @property stats the live stat values this archive resets.
 */
class SeasonStore(private val storage: AddonStorage, private val stats: StatsStore) {
    private val json = Json { prettyPrint = true }
    private val seasons = mutableMapOf<String, MutableList<SeasonRecord>>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            val document = json.decodeFromString<SeasonsDocument>(raw)
            document.seasons.forEach { (stat, records) -> seasons[stat] = records.toMutableList() }
        }
    }

    /**
     * Archives the current standings of a stat and clears it for a new season.
     *
     * @param stat stat key, case-insensitive.
     * @param epochMs archive timestamp.
     * @return the archived record, or `null` when the stat had no values to archive.
     */
    @Synchronized
    fun reset(stat: String, epochMs: Long): SeasonRecord? {
        val key = stat.lowercase()
        val standings = stats.snapshot(key)
        if (standings.isEmpty()) {
            return null
        }
        val record = SeasonRecord(
            season = (seasons[key]?.maxOfOrNull { it.season } ?: 0) + 1,
            endedAtEpochMs = epochMs,
            standings = standings.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .map { PlayerScore(it.key, it.value) },
        )
        seasons.getOrPut(key) { mutableListOf() }.add(record)
        persist()
        stats.clear(key)
        return record
    }

    /**
     * Lists archived seasons for a stat, oldest first.
     *
     * @param stat stat key, case-insensitive.
     * @return the archived seasons, empty when none were reset yet.
     */
    @Synchronized
    fun seasons(stat: String): List<SeasonRecord> = seasons[stat.lowercase()]?.toList() ?: emptyList()

    /**
     * Looks up one archived season.
     *
     * @param stat stat key, case-insensitive.
     * @param season season number.
     * @return the record, or `null` when that season is unknown.
     */
    @Synchronized
    fun season(stat: String, season: Int): SeasonRecord? =
        seasons[stat.lowercase()]?.firstOrNull { it.season == season }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(SeasonsDocument(seasons.mapValues { it.value.toList() })))
    }

    private companion object {
        /** Document key holding all season archives. */
        const val DOCUMENT = "seasons"
    }
}
