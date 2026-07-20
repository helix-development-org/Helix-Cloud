package org.helix.addons.bans

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Ban persistence backed by the addon's document storage (files or
 * PostgreSQL, depending on the node's storage mode).
 *
 * @property storage addon-scoped document store.
 * @property clock epoch millis source, injectable for tests.
 */
class BanStore(
    private val storage: AddonStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val bans = linkedMapOf<String, BanEntry>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            json.decodeFromString<List<BanEntry>>(raw).forEach { bans[it.player] = it }
        }
    }

    /**
     * Creates or replaces a ban.
     *
     * @param player player name, matched case-insensitively.
     * @param reason human readable reason.
     * @param durationMs ban duration; `null` for permanent.
     * @return the persisted entry.
     */
    @Synchronized
    fun set(player: String, reason: String, durationMs: Long? = null): BanEntry {
        val now = clock()
        val entry = BanEntry(
            player = player.lowercase(),
            reason = reason,
            createdAtEpochMs = now,
            expiresAtEpochMs = durationMs?.let { now + it },
        )
        bans[entry.player] = entry
        persist()
        return entry
    }

    /**
     * Removes a ban.
     *
     * @param player player name, matched case-insensitively.
     * @return `true` if a ban existed.
     */
    @Synchronized
    fun pardon(player: String): Boolean {
        val removed = bans.remove(player.lowercase()) != null
        if (removed) {
            persist()
        }
        return removed
    }

    /**
     * Looks up the active ban of a player, pruning it when expired.
     *
     * @param player player name, matched case-insensitively.
     * @return the active ban or `null`.
     */
    @Synchronized
    fun activeBan(player: String): BanEntry? {
        val entry = bans[player.lowercase()] ?: return null
        if (!entry.active(clock())) {
            bans.remove(entry.player)
            persist()
            return null
        }
        return entry
    }

    /**
     * Lists all active bans, pruning expired ones.
     *
     * @return active bans sorted by player name.
     */
    @Synchronized
    fun all(): List<BanEntry> {
        val now = clock()
        val expired = bans.values.filter { !it.active(now) }.map { it.player }
        if (expired.isNotEmpty()) {
            expired.forEach(bans::remove)
            persist()
        }
        return bans.values.sortedBy { it.player }
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(bans.values.toList()))
    }

    private companion object {
        /** Document key holding the ban list. */
        const val DOCUMENT = "bans"
    }
}
