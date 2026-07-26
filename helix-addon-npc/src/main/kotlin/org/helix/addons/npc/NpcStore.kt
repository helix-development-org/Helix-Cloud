package org.helix.addons.npc

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Network-wide NPC persistence backed by the addon's document storage.
 *
 * Every definition lives in a single document (key [DEFS_KEY]) mapping the
 * NPC id to its [NpcDef]. Keeping them in one document makes listing and the
 * per-task filter a plain in-memory scan; the definition count is small and
 * bounded by [MAX_NPCS], so this stays cheap.
 *
 * All methods are synchronized because actions may be invoked concurrently.
 *
 * @property storage addon-scoped document store.
 */
class NpcStore(private val storage: AddonStorage) {
    private val json = Json

    /**
     * Inserts or replaces a definition.
     *
     * @param def the definition to persist, keyed by its [NpcDef.id].
     * @throws IllegalStateException when storing a new NPC would exceed
     *   [MAX_NPCS].
     */
    @Synchronized
    fun upsert(def: NpcDef) {
        val defs = readAll().toMutableMap()
        if (def.id !in defs) {
            check(defs.size < MAX_NPCS) { "npc limit reached ($MAX_NPCS)" }
        }
        defs[def.id] = def
        storage.write(DEFS_KEY, json.encodeToString(defs.toMap()))
    }

    /**
     * Removes a definition.
     *
     * @param id the NPC id, any case.
     * @return `true` when a definition was removed, `false` when none matched.
     */
    @Synchronized
    fun delete(id: String): Boolean {
        val defs = readAll().toMutableMap()
        val removed = defs.remove(id.lowercase()) != null
        if (removed) {
            storage.write(DEFS_KEY, json.encodeToString(defs.toMap()))
        }
        return removed
    }

    /**
     * Reads a single definition.
     *
     * @param id the NPC id, any case.
     * @return the definition, or `null` when none matched.
     */
    @Synchronized
    fun get(id: String): NpcDef? = readAll()[id.lowercase()]

    /**
     * Lists definitions, optionally scoped to a task.
     *
     * @param task the task to filter by, or `null` for every definition.
     *   When set, definitions whose task equals [task] or `*` are returned,
     *   so wildcard NPCs appear on every task.
     * @return the matching definitions, sorted by id.
     */
    @Synchronized
    fun list(task: String? = null): List<NpcDef> = readAll().values
        .filter { task == null || it.task == "*" || it.task.equals(task, ignoreCase = true) }
        .sortedBy { it.id }

    private fun readAll(): Map<String, NpcDef> =
        storage.read(DEFS_KEY)?.let { json.decodeFromString<Map<String, NpcDef>>(it) } ?: emptyMap()

    private companion object {
        /** Document key holding the id-to-definition map. */
        const val DEFS_KEY = "defs"

        /** Upper bound on the number of stored NPC definitions. */
        const val MAX_NPCS = 500
    }
}
