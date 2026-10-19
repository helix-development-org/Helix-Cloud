package org.helix.addons.economy

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * One-time move of the pre-crown `balances` document (player name →
 * coins) to uuid accounts. Names the node's identity registry can resolve
 * are imported right away; the others are parked in
 * [UNRESOLVED] and claimed when that player joins next. Afterwards the old
 * document is kept as [MIGRATED] for reference, so the migration never runs
 * twice.
 *
 * @property storage the addon's document storage.
 * @property repository target repository.
 * @property resolveUuid identity lookup (lowercase name → uuid).
 */
class LegacyMigration(
    private val storage: AddonStorage,
    private val repository: BalanceRepository,
    private val resolveUuid: (String) -> String?,
) {
    private val json = Json

    /**
     * Migrates the legacy document if it still exists.
     *
     * @return number of imported accounts.
     */
    @Synchronized
    fun run(): Int {
        val raw = storage.read(LEGACY) ?: return 0
        val legacy = json.decodeFromString<Map<String, Long>>(raw)
        val unresolved = readUnresolved().toMutableMap()
        var imported = 0
        legacy.forEach { (name, amount) ->
            val uuid = resolveUuid(name.lowercase())
            if (uuid == null) {
                unresolved[name.lowercase()] = amount
            } else if (repository.importBalance(Holder(uuid, name), amount)) {
                imported++
            }
        }
        storage.write(UNRESOLVED, json.encodeToString(unresolved.toMap()))
        storage.write(MIGRATED, raw)
        storage.delete(LEGACY)
        return imported
    }

    /**
     * Claims a parked legacy balance for a joining player.
     *
     * @param holder the joining player.
     * @return whether a balance was imported.
     */
    @Synchronized
    fun claim(holder: Holder): Boolean {
        val unresolved = readUnresolved()
        val amount = unresolved[holder.name.lowercase()] ?: return false
        storage.write(UNRESOLVED, json.encodeToString(unresolved - holder.name.lowercase()))
        return repository.importBalance(holder, amount)
    }

    /**
     * Counts or drops the parked legacy balances (release reset: nobody may import old coins afterwards).
     *
     * @param dryRun `true` only counts.
     * @return number of parked balances.
     */
    @Synchronized
    fun clearUnresolved(dryRun: Boolean): Int {
        val count = readUnresolved().size
        if (!dryRun && count > 0) storage.write(UNRESOLVED, json.encodeToString(emptyMap<String, Long>()))
        return count
    }

    private fun readUnresolved(): Map<String, Long> =
        storage.read(UNRESOLVED)?.let { json.decodeFromString<Map<String, Long>>(it) }.orEmpty()

    /** Storage keys of the migration. */
    companion object {
        /** The pre-crown document: lowercase name → coins. */
        const val LEGACY = "balances"

        /** Names that could not be resolved yet, claimed on join. */
        const val UNRESOLVED = "balances.unresolved"

        /** The legacy document after migration (kept for reference). */
        const val MIGRATED = "balances.migrated"
    }
}
