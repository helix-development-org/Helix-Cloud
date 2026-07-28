package org.helix.addons.moderation

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Mute persistence backed by the addon's document storage, mirroring
 * [org.helix.addons.bans.BanStore]'s shape.
 *
 * @property storage addon-scoped document store.
 * @property clock epoch millis source, injectable for tests.
 */
class MuteStore(
    private val storage: AddonStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val mutes = linkedMapOf<String, MuteEntry>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            json.decodeFromString<List<MuteEntry>>(raw).forEach { mutes[it.player] = it }
        }
    }

    /**
     * Creates or replaces a mute.
     *
     * @param player player name, matched case-insensitively.
     * @param reason human readable reason.
     * @param durationMs mute duration; `null` for permanent.
     * @param issuedBy the staff member issuing the mute.
     * @return the persisted entry.
     */
    @Synchronized
    fun set(player: String, reason: String, durationMs: Long? = null, issuedBy: String = ""): MuteEntry {
        val now = clock()
        val entry = MuteEntry(
            player = player.lowercase(),
            reason = reason,
            createdAtEpochMs = now,
            expiresAtEpochMs = durationMs?.let { now + it },
            issuedBy = issuedBy,
        )
        mutes[entry.player] = entry
        persist()
        return entry
    }

    /**
     * Removes a mute.
     *
     * @param player player name, matched case-insensitively.
     * @return `true` if a mute existed.
     */
    @Synchronized
    fun unmute(player: String): Boolean {
        val removed = mutes.remove(player.lowercase()) != null
        if (removed) {
            persist()
        }
        return removed
    }

    /**
     * Looks up the active mute of a player, pruning it when expired.
     *
     * @param player player name, matched case-insensitively.
     * @return the active mute or `null`.
     */
    @Synchronized
    fun activeMute(player: String): MuteEntry? {
        val entry = mutes[player.lowercase()] ?: return null
        if (!entry.active(clock())) {
            mutes.remove(entry.player)
            persist()
            return null
        }
        return entry
    }

    /**
     * Lists all active mutes, pruning expired ones.
     *
     * @return active mutes sorted by player name.
     */
    @Synchronized
    fun all(): List<MuteEntry> {
        val now = clock()
        val expired = mutes.values.filter { !it.active(now) }.map { it.player }
        if (expired.isNotEmpty()) {
            expired.forEach(mutes::remove)
            persist()
        }
        return mutes.values.sortedBy { it.player }
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(mutes.values.toList()))
    }

    private companion object {
        /** Document key holding the mute list. */
        const val DOCUMENT = "mutes"
    }
}
