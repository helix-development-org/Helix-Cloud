package org.helix.addons.bans

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Ban persistence backed by the addon's document storage (files or
 * PostgreSQL, depending on the node's storage mode).
 *
 * Entries are keyed on uuid once known, falling back to the lowercase name
 * for players this node has never seen join. A name-keyed entry is migrated
 * to its uuid the first time that uuid becomes resolvable (a join, or an
 * explicit [resolveUuid] hit), which is what stops a rename from evading a
 * ban — see [migrateIfKnown].
 *
 * @property storage addon-scoped document store.
 * @property resolveUuid resolves a player name to its current owner's uuid,
 *  typically the node's identity registry via `AddonContext.resolvePlayerUuid`.
 * @property clock epoch millis source, injectable for tests.
 */
class BanStore(
    private val storage: AddonStorage,
    private val resolveUuid: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val bans = linkedMapOf<String, BanEntry>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            json.decodeFromString<List<BanEntry>>(raw).forEach { bans[it.uuid ?: it.player] = it }
        }
    }

    /**
     * Creates or replaces a ban.
     *
     * @param player player name, matched case-insensitively.
     * @param reason human readable reason.
     * @param durationMs ban duration; `null` for permanent.
     * @param uuid the joining player's uuid, when set directly by a join
     *  gate check; otherwise resolved from [resolveUuid].
     * @return the persisted entry.
     */
    @Synchronized
    fun set(player: String, reason: String, durationMs: Long? = null, uuid: String? = null): BanEntry {
        val name = player.lowercase()
        val resolved = uuid ?: resolveUuid(name)
        val now = clock()
        val entry = BanEntry(
            player = name,
            reason = reason,
            createdAtEpochMs = now,
            expiresAtEpochMs = durationMs?.let { now + it },
            uuid = resolved,
        )
        bans.remove(name)
        bans[resolved ?: name] = entry
        persist()
        return entry
    }

    /**
     * Removes a ban.
     *
     * @param player player name, matched case-insensitively.
     * @param uuid the player's uuid, when known directly; otherwise resolved
     *  from [resolveUuid].
     * @return `true` if a ban existed.
     */
    @Synchronized
    fun pardon(player: String, uuid: String? = null): Boolean {
        val key = keyOf(player, uuid)
        val removed = bans.remove(key) != null
        if (removed) {
            persist()
        }
        return removed
    }

    /**
     * Looks up the active ban of a player, pruning it when expired.
     *
     * @param player player name, matched case-insensitively.
     * @param uuid the joining player's uuid, when set directly by a join
     *  gate check (the value that actually defeats rename evasion);
     *  otherwise resolved from [resolveUuid].
     * @return the active ban or `null`.
     */
    @Synchronized
    fun activeBan(player: String, uuid: String? = null): BanEntry? {
        val key = keyOf(player, uuid)
        val entry = bans[key] ?: return null
        if (!entry.active(clock())) {
            bans.remove(key)
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
        val expired = bans.entries.filter { !it.value.active(now) }.map { it.key }
        if (expired.isNotEmpty()) {
            expired.forEach(bans::remove)
            persist()
        }
        return bans.values.sortedBy { it.player }
    }

    /**
     * Resolves the storage key for [player] and migrates a legacy name-keyed
     * entry to its uuid the moment that uuid becomes known.
     *
     * @param player player name.
     * @param uuidHint uuid supplied directly by the caller (for example the
     *  uuid on a [org.helix.api.proxy.JoinRequest]), preferred over [resolveUuid].
     * @return the map key to use: the uuid when known, else the lowercase name.
     */
    private fun keyOf(player: String, uuidHint: String?): String {
        val name = player.lowercase()
        val resolved = uuidHint ?: resolveUuid(name)
        return migrateIfKnown(name, resolved)
    }

    /**
     * Moves a name-keyed entry to its uuid key once the uuid is known,
     * carrying its data forward unchanged (the fix for name-based ban
     * evasion: future lookups by uuid find it regardless of current name).
     *
     * @param name the lowercase name a legacy entry may be keyed under.
     * @param resolved the now-known uuid, or `null` when still unknown.
     * @return the key to use for this operation.
     */
    private fun migrateIfKnown(name: String, resolved: String?): String {
        if (resolved == null) {
            return name
        }
        val legacy = bans[name]
        if (legacy != null && legacy.uuid == null) {
            bans.remove(name)
            if (resolved !in bans) {
                bans[resolved] = legacy.copy(uuid = resolved)
            }
            persist()
        }
        return resolved
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(bans.values.toList()))
    }

    private companion object {
        /** Document key holding the ban list. */
        const val DOCUMENT = "bans"
    }
}
