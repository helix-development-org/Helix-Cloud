package org.helix.addons.mineclash.war

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/** Persistence of clan wars (contract §4): PostgreSQL `mineclash.wars` or the addon document store. */
interface WarStore : AutoCloseable {
    /**
     * Wars that still occupy their clans ([WarState.ACTIVE]), oldest first.
     *
     * @return the active wars.
     */
    fun loadActive(): List<WarRecord>

    /**
     * Loads one war.
     *
     * @param warId war id.
     * @return the war or null.
     */
    fun load(warId: String): WarRecord?

    /**
     * Inserts or replaces a war.
     *
     * @param record the war.
     */
    fun save(record: WarRecord)

    /**
     * The next free war id of a day: `w-<day>-<nnnn>`.
     *
     * @param day `yyyyMMdd`.
     * @return the id.
     */
    fun nextId(day: String): String

    /**
     * Release reset: deletes every war.
     *
     * @param dryRun only count.
     * @return deleted (or deletable) entries per kind.
     */
    fun wipe(dryRun: Boolean): Map<String, Int>

    /** Releases resources (connection pool). */
    override fun close() {
    }

    /** Id helpers shared by the implementations. */
    companion object {
        /**
         * Formats a war id.
         *
         * @param day `yyyyMMdd`.
         * @param sequence 1-based number of the war on that day.
         * @return `w-<day>-<nnnn>`.
         */
        fun id(day: String, sequence: Int): String = "w-$day-" + sequence.toString().padStart(4, '0')

        /**
         * The sequence number of a war id of [day].
         *
         * @param day `yyyyMMdd`.
         * @param warId a war id.
         * @return the number, 0 when the id belongs to another day or is malformed.
         */
        fun sequenceOf(day: String, warId: String): Int =
            warId.removePrefix("w-$day-").takeIf { it != warId }?.toIntOrNull() ?: 0
    }
}

/**
 * [WarStore] on the addon document store: one document per war (`war.<warId>`).
 *
 * @property storage the addon storage.
 */
class DocumentWarStore(private val storage: AddonStorage) : WarStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun loadActive(): List<WarRecord> =
        all().filter { it.state in WarState.ACTIVE }.sortedBy { it.acceptedAt }

    override fun load(warId: String): WarRecord? =
        storage.read(PREFIX + warId)?.let { runCatching { json.decodeFromString(WarRecord.serializer(), it) }.getOrNull() }

    override fun save(record: WarRecord) {
        storage.write(PREFIX + record.warId, json.encodeToString(WarRecord.serializer(), record))
    }

    @Synchronized
    override fun nextId(day: String): String {
        val last = storage.keys().filter { it.startsWith(PREFIX) }.maxOfOrNull { WarStore.sequenceOf(day, it.removePrefix(PREFIX)) } ?: 0
        return WarStore.id(day, last + 1)
    }

    override fun wipe(dryRun: Boolean): Map<String, Int> {
        val keys = storage.keys().filter { it.startsWith(PREFIX) }
        if (!dryRun) keys.forEach(storage::delete)
        return linkedMapOf("wars" to keys.size)
    }

    private fun all(): List<WarRecord> = storage.keys().filter { it.startsWith(PREFIX) }.mapNotNull { load(it.removePrefix(PREFIX)) }

    private companion object {
        /** Document key prefix of wars. */
        const val PREFIX = "war."
    }
}
