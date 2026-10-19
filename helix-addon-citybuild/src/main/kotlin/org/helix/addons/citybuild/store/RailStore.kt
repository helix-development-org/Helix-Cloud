package org.helix.addons.citybuild.store

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Persistence of the rail network (game concept A21.4): which stations a player has discovered on which
 * instance. Kept apart from [CityRepository] so the rail feature owns its storage.
 */
interface RailStore : AutoCloseable {
    /**
     * @param uuid player.
     * @param shard instance.
     * @return ids of the stations the player discovered on the instance, sorted.
     */
    fun discovered(uuid: String, shard: String): List<String>

    /**
     * Marks a station as discovered.
     *
     * @param uuid player.
     * @param shard instance.
     * @param station station id.
     * @param at discovery time in epoch milliseconds.
     * @return `true` when the station was new for the player, `false` when it was known already.
     */
    fun discover(uuid: String, shard: String, station: String, at: Long): Boolean

    /**
     * Forgets every discovery of a player (GDPR delete).
     *
     * @param uuid player.
     * @return `true` when something was removed.
     */
    fun forget(uuid: String): Boolean

    /**
     * @param uuid player.
     * @return instance id to discovered station ids (GDPR export).
     */
    fun export(uuid: String): Map<String, List<String>>

    /** Releases resources (no-op for the document store). */
    /**
     * Counts or deletes every discovered-station record (release reset).
     *
     * @param dryRun `true` only counts.
     * @return number of records (counted before deletion).
     */
    fun wipe(dryRun: Boolean): Int

    override fun close() {}
}

/**
 * [RailStore] on the addon document store: one document `rail_discovered:<uuid>` holding instance to station
 * ids. Writes are serialized on this instance.
 *
 * @property storage the addon document store.
 */
class DocumentRailStore(private val storage: AddonStorage) : RailStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun discovered(uuid: String, shard: String): List<String> = read(uuid)[shard].orEmpty().sorted()

    @Synchronized
    override fun discover(uuid: String, shard: String, station: String, at: Long): Boolean {
        val all = read(uuid).mapValues { it.value.toMutableSet() }.toMutableMap()
        val set = all.getOrPut(shard) { mutableSetOf() }
        if (!set.add(station)) return false
        storage.write(key(uuid), json.encodeToString(all.mapValues { it.value.sorted() }))
        return true
    }

    @Synchronized
    override fun forget(uuid: String): Boolean {
        if (storage.read(key(uuid)) == null) return false
        storage.delete(key(uuid))
        return true
    }

    @Synchronized
    override fun export(uuid: String): Map<String, List<String>> = read(uuid).mapValues { it.value.sorted() }

    @Synchronized
    override fun wipe(dryRun: Boolean): Int {
        val keys = storage.keys().filter { it.startsWith(KEY_PREFIX) }
        if (!dryRun) keys.forEach(storage::delete)
        return keys.size
    }

    private fun read(uuid: String): Map<String, List<String>> =
        storage.read(key(uuid))?.let { runCatching { json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()

    private fun key(uuid: String) = KEY_PREFIX + uuid

    private companion object {
        const val KEY_PREFIX = "rail_discovered:"
    }
}
