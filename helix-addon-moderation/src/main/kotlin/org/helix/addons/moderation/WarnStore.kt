package org.helix.addons.moderation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * JSON-file backed warn history.
 *
 * @property file the `warns.json` path.
 * @property clock epoch millis source, injectable for tests.
 */
class WarnStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val warns = mutableListOf<WarnEntry>()

    init {
        if (Files.exists(file)) {
            warns += json.decodeFromString<List<WarnEntry>>(Files.readString(file))
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
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(warns.toList()))
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
}
