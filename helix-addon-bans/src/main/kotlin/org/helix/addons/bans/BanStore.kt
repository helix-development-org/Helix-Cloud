package org.helix.addons.bans

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A single network ban.
 *
 * @property player banned player name, lowercase.
 * @property reason human readable reason.
 * @property createdAtEpochMs when the ban was issued.
 * @property expiresAtEpochMs when the ban ends; `null` for permanent bans.
 */
@Serializable
data class BanEntry(
    val player: String,
    val reason: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
) {
    /**
     * Whether the ban is active at the given time.
     *
     * @param nowEpochMs current epoch millis.
     * @return `true` while permanent or not yet expired.
     */
    fun active(nowEpochMs: Long): Boolean =
        expiresAtEpochMs == null || expiresAtEpochMs > nowEpochMs
}

/**
 * JSON-file backed ban persistence in the addon data directory.
 *
 * @property file the `bans.json` path.
 * @property clock epoch millis source, injectable for tests.
 */
class BanStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val bans = linkedMapOf<String, BanEntry>()

    init {
        if (Files.exists(file)) {
            json.decodeFromString<List<BanEntry>>(Files.readString(file))
                .forEach { bans[it.player] = it }
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
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(bans.values.toList()))
    }
}
