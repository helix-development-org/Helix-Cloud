package org.helix.addons.moderation

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Warn history backed by the addon's document storage.
 *
 * @property storage addon-scoped document store.
 * @property clock epoch millis source, injectable for tests.
 */
class WarnStore(
    private val storage: AddonStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val warns = mutableListOf<WarnEntry>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            warns += json.decodeFromString<List<WarnEntry>>(raw)
        }
    }

    /**
     * Records a warning.
     *
     * @param player warned player.
     * @param by warning moderator.
     * @param reason warning reason.
     * @return the persisted entry.
     */
    @Synchronized
    fun warn(player: String, by: String, reason: String): WarnEntry {
        val entry = WarnEntry(player.lowercase(), by, reason, clock())
        warns += entry
        storage.write(DOCUMENT, json.encodeToString(warns.toList()))
        return entry
    }

    /**
     * Lists all warnings of a player, newest first.
     *
     * @param player the player.
     * @return warnings sorted by time descending.
     */
    @Synchronized
    fun warnsOf(player: String): List<WarnEntry> =
        warns.filter { it.player == player.lowercase() }.sortedByDescending { it.atEpochMs }

    private companion object {
        /** Document key holding the warn history. */
        const val DOCUMENT = "warns"
    }
}
