package org.helix.addons.guard

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Persistence for IGuard config overrides.
 *
 * Overrides live in the addon's document storage as a single JSON object
 * mapping dotted config paths to canonical string values; defaults come
 * from [GuardConfig] and are never persisted.
 *
 * @property storage addon-scoped document store.
 */
class GuardConfigStore(private val storage: AddonStorage) {
    private val json = Json

    /**
     * Reads all stored overrides.
     *
     * @return dotted path to canonical value, empty when nothing is overridden.
     */
    fun overrides(): Map<String, String> =
        storage.read(DOCUMENT)?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()

    /**
     * Stores (creates or replaces) one override.
     *
     * @param path dotted config path.
     * @param value canonical value, already validated for the setting's type.
     */
    fun set(path: String, value: String) {
        persist(overrides() + (path to value))
    }

    /**
     * Removes one override.
     *
     * @param path dotted config path.
     * @return `true` when an override existed.
     */
    fun remove(path: String): Boolean {
        val current = overrides()
        if (path !in current) {
            return false
        }
        persist(current - path)
        return true
    }

    /**
     * Removes all overrides.
     *
     * @return the paths that were overridden, sorted.
     */
    fun clear(): List<String> {
        val removed = overrides().keys.sorted()
        if (removed.isNotEmpty()) {
            storage.delete(DOCUMENT)
        }
        return removed
    }

    private fun persist(overrides: Map<String, String>) {
        if (overrides.isEmpty()) {
            storage.delete(DOCUMENT)
        } else {
            storage.write(DOCUMENT, json.encodeToString(overrides))
        }
    }

    private companion object {
        /** Storage document holding the override map. */
        const val DOCUMENT = "config"
    }
}
