package org.helix.addons.maprotation

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Map/world rotation state, backed by the addon's document storage.
 *
 * A rotation is a named, ordered list of maps and a cursor into it. The
 * node owns this state (rather than each Paper server independently) so the
 * same rotation is visible and manageable from one place; a Paper-side
 * bridge (or a server's own plugin) is expected to call [advance] on a
 * timer or round-end signal and react to the resulting map name — this
 * store only decides and remembers which map is next, it never loads
 * worlds or teleports anyone.
 *
 * @property storage addon-scoped document store.
 */
class RotationStore(private val storage: AddonStorage) {
    private val json = Json { prettyPrint = true }
    private val rotations = mutableMapOf<String, RotationState>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            rotations.putAll(json.decodeFromString<RotationsDocument>(raw).rotations)
        }
    }

    /**
     * Creates or replaces a rotation's map list.
     *
     * Keeps the current cursor when the previous current map still appears
     * in the new list (at its new position); otherwise resets to the first
     * map.
     *
     * @param id rotation id, case-insensitive.
     * @param maps ordered map/world names; must not be empty.
     * @return the resulting state.
     * @throws IllegalArgumentException when [maps] is empty.
     */
    @Synchronized
    fun configure(id: String, maps: List<String>): RotationState {
        require(maps.isNotEmpty()) { "maps must not be empty" }
        val key = id.lowercase()
        val previousCurrent = rotations[key]?.let { it.maps.getOrNull(it.currentIndex) }
        val index = previousCurrent?.let { maps.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val state = RotationState(maps = maps, currentIndex = index)
        rotations[key] = state
        persist()
        return state
    }

    /**
     * Removes a rotation entirely.
     *
     * @param id rotation id, case-insensitive.
     * @return `false` when no such rotation existed.
     */
    @Synchronized
    fun remove(id: String): Boolean {
        val removed = rotations.remove(id.lowercase())
        if (removed != null) {
            persist()
        }
        return removed != null
    }

    /**
     * Reads the current map of a rotation.
     *
     * @param id rotation id, case-insensitive.
     * @return the current map, or `null` when the rotation is unknown or has no maps.
     */
    @Synchronized
    fun current(id: String): String? = rotations[id.lowercase()]?.let { it.maps.getOrNull(it.currentIndex) }

    /**
     * Peeks at the map a rotation would move to on the next [advance], without changing it.
     *
     * @param id rotation id, case-insensitive.
     * @return the upcoming map, or `null` when the rotation is unknown or has no maps.
     */
    @Synchronized
    fun peekNext(id: String): String? {
        val state = rotations[id.lowercase()] ?: return null
        if (state.maps.isEmpty()) {
            return null
        }
        return state.maps[(state.currentIndex + 1) % state.maps.size]
    }

    /**
     * Advances a rotation to its next map, wrapping around at the end.
     *
     * @param id rotation id, case-insensitive.
     * @return the new current map, or `null` when the rotation is unknown or has no maps.
     */
    @Synchronized
    fun advance(id: String): String? {
        val key = id.lowercase()
        val state = rotations[key] ?: return null
        if (state.maps.isEmpty()) {
            return null
        }
        val updated = state.copy(currentIndex = (state.currentIndex + 1) % state.maps.size)
        rotations[key] = updated
        persist()
        return updated.maps[updated.currentIndex]
    }

    /**
     * Lists all configured rotation ids.
     *
     * @return rotation ids sorted alphabetically.
     */
    @Synchronized
    fun rotationIds(): List<String> = rotations.keys.sorted()

    /**
     * Reads a rotation's full configured map list.
     *
     * @param id rotation id, case-insensitive.
     * @return the maps in cycle order, empty when the rotation is unknown.
     */
    @Synchronized
    fun mapsOf(id: String): List<String> = rotations[id.lowercase()]?.maps ?: emptyList()

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(RotationsDocument(rotations.toMap())))
    }

    private companion object {
        /** Document key holding all rotations. */
        const val DOCUMENT = "rotations"
    }
}
