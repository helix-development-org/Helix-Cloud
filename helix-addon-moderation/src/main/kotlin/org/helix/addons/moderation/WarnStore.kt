package org.helix.addons.moderation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.helix.api.storage.AddonStorage
import org.helix.api.storage.SchemaMigrator

/**
 * Warn history backed by the addon's document storage.
 *
 * Persisted through [SchemaMigrator] as the sanctioned example of that
 * pattern: the document carries a `schemaVersion`, so a future format change
 * only needs a new migration entry, not a one-off ad-hoc migration.
 *
 * Entries are tagged with the warned player's uuid once known, and matched
 * on it instead of the name from that point on — a legacy name-only entry
 * is tagged the first time that name's uuid becomes resolvable, so a rename
 * afterwards does not detach the player from their own warn history.
 *
 * Warns expire after [expiryMillis]: [warnsOf] (and therefore every active-warn
 * count derived from it) excludes entries older than the configured window,
 * so a warning from months ago no longer counts towards escalation, while the
 * full record stays on disk for as long as the underlying entry is retained.
 *
 * @property storage addon-scoped document store.
 * @property resolveUuid resolves a player name to its current owner's uuid,
 *  typically the node's identity registry via `AddonContext.resolvePlayerUuid`.
 * @property expiryMillis how long a warning stays active; re-read on every
 *   call so a live config change takes effect immediately.
 * @property clock epoch millis source, injectable for tests.
 */
class WarnStore(
    private val storage: AddonStorage,
    private val resolveUuid: (String) -> String? = { null },
    private val expiryMillis: () -> Long = { DEFAULT_EXPIRY_MILLIS },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val migrator = SchemaMigrator(
        currentVersion = SCHEMA_VERSION,
        // version 0 documents (pre-SchemaMigrator) are a bare warn list —
        // already the right shape, so the migration is the identity.
        migrations = mapOf(0 to { body -> body }),
    )
    private val warns = mutableListOf<WarnEntry>()

    init {
        migrator.read(storage, DOCUMENT)?.let { body ->
            warns += json.decodeFromJsonElement<List<WarnEntry>>(body)
        }
    }

    /**
     * Records a warning.
     *
     * @param player warned player.
     * @param by warning moderator.
     * @param reason warning reason.
     * @param uuid the warned player's uuid, when known directly; otherwise
     *  resolved from [resolveUuid].
     * @return the persisted entry.
     */
    @Synchronized
    fun warn(player: String, by: String, reason: String, uuid: String? = null): WarnEntry {
        val name = player.lowercase()
        val resolved = uuid ?: resolveUuid(name)
        migrateIfKnown(name, resolved)
        val entry = WarnEntry(name, by, reason, clock(), uuid = resolved)
        warns += entry
        persist()
        return entry
    }

    /**
     * Lists a player's still-active warnings, newest first.
     *
     * Warnings older than [expiryMillis] are excluded (but not deleted, so
     * they still stand as a permanent record if ever needed) — every caller
     * that derives an active-warn count reads through this method.
     *
     * Matched on uuid once known — a legacy name-only entry is tagged with
     * it first, so the history follows the player through a rename.
     *
     * @param player the player.
     * @param uuid the player's uuid, when known directly; otherwise resolved
     *  from [resolveUuid].
     * @return active warnings sorted by time descending.
     */
    @Synchronized
    fun warnsOf(player: String, uuid: String? = null): List<WarnEntry> {
        val name = player.lowercase()
        val resolved = uuid ?: resolveUuid(name)
        migrateIfKnown(name, resolved)
        val cutoff = clock() - expiryMillis()
        return warns
            .filter { if (resolved != null) it.uuid == resolved else it.player == name }
            .filter { it.atEpochMs >= cutoff }
            .sortedByDescending { it.atEpochMs }
    }

    /**
     * Tags every legacy name-only entry of [name] with [resolved], once it
     * becomes known — the carry-forward that keeps a rename from detaching a
     * player from their own warn history.
     *
     * @param name the lowercase name legacy entries may carry.
     * @param resolved the now-known uuid, or `null` when still unknown.
     */
    private fun migrateIfKnown(name: String, resolved: String?) {
        if (resolved == null) {
            return
        }
        var changed = false
        for (i in warns.indices) {
            val entry = warns[i]
            if (entry.uuid == null && entry.player == name) {
                warns[i] = entry.copy(uuid = resolved)
                changed = true
            }
        }
        if (changed) {
            persist()
        }
    }

    private fun persist() {
        migrator.write(storage, DOCUMENT, json.encodeToJsonElement(warns.toList()))
    }

    /**
     * Removes every warning of a player. Used by GDPR delete requests.
     *
     * @param player the player.
     * @return `true` when any warning was removed.
     */
    @Synchronized
    fun clear(player: String): Boolean {
        val removed = warns.removeAll { it.player == player.lowercase() }
        if (removed) {
            storage.write(DOCUMENT, json.encodeToString(warns.toList()))
        }
        return removed
    }

    private companion object {
        /** Document key holding the warn history. */
        const val DOCUMENT = "warns"

        /** Current schema version of the `warns` document. */
        const val SCHEMA_VERSION = 1

        /** Fallback active-warn window when the addon config carries no override. */
        const val DEFAULT_EXPIRY_MILLIS = 30L * 24 * 3_600_000
    }
}
