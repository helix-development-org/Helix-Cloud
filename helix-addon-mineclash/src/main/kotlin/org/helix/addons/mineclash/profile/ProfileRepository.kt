package org.helix.addons.mineclash.profile

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Persistence of player profiles and match history.
 */
interface ProfileRepository {
    /**
     * Loads a profile.
     *
     * @param uuid player uuid.
     * @return the profile or `null` when the player is unknown.
     */
    fun load(uuid: String): PlayerProfile?

    /**
     * Creates or replaces a profile.
     *
     * @param profile the profile to store.
     */
    fun save(profile: PlayerProfile)

    /**
     * Appends a match record.
     *
     * @param record the finished match.
     */
    fun recordMatch(record: MatchRecord)

    /**
     * Ranks players by roses.
     *
     * @param limit maximum entries.
     * @return uuid, name and roses, best first.
     */
    fun topByRoses(limit: Int): List<Triple<String, String, Long>>

    /** Releases resources such as connection pools. */
    fun close() {}
}

/**
 * Profile repository on the addon document store (`profile.<uuid>` documents, `matches` history
 * capped at [MAX_MATCHES]). Used when the node storage is not PostgreSQL and in tests.
 *
 * @property storage addon-scoped document store.
 */
class DocumentProfileRepository(private val storage: AddonStorage) : ProfileRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(uuid: String): PlayerProfile? =
        storage.read(PROFILE_PREFIX + uuid)?.let { json.decodeFromString<PlayerProfile>(it) }

    override fun save(profile: PlayerProfile) {
        storage.write(PROFILE_PREFIX + profile.uuid, json.encodeToString(profile))
    }

    @Synchronized
    override fun recordMatch(record: MatchRecord) {
        val history = storage.read(MATCHES_KEY)?.let { json.decodeFromString<List<MatchRecord>>(it) }.orEmpty()
        storage.write(MATCHES_KEY, json.encodeToString((history + record).takeLast(MAX_MATCHES)))
    }

    override fun topByRoses(limit: Int): List<Triple<String, String, Long>> =
        storage.keys().filter { it.startsWith(PROFILE_PREFIX) }
            .mapNotNull { key -> load(key.removePrefix(PROFILE_PREFIX)) }
            .sortedByDescending { it.roses }
            .take(limit)
            .map { Triple(it.uuid, it.name, it.roses) }

    /** Companion with storage keys. */
    companion object {
        /** Document key prefix of profiles. */
        const val PROFILE_PREFIX: String = "profile."

        /** Document key of the match history. */
        const val MATCHES_KEY: String = "matches"

        /** Number of match records kept in the document history. */
        const val MAX_MATCHES: Int = 500
    }
}
