package org.helix.addons.stats

import kotlinx.serialization.Serializable

/**
 * A stat's archived standings at the end of one season.
 *
 * @property season season number, starting at `1`, incremented on every reset.
 * @property endedAtEpochMs epoch millis when the season was archived.
 * @property standings final standings, highest value first.
 */
@Serializable
data class SeasonRecord(
    val season: Int,
    val endedAtEpochMs: Long,
    val standings: List<PlayerScore>,
)

/**
 * A single player's value within an archived [SeasonRecord].
 *
 * @property player player name.
 * @property value the stat's final value for this player.
 */
@Serializable
data class PlayerScore(
    val player: String,
    val value: Long,
)

/**
 * Persisted seasonal archive.
 *
 * @property seasons stat key (lowercase) to its archived seasons, oldest first.
 */
@Serializable
data class SeasonsDocument(
    val seasons: Map<String, List<SeasonRecord>> = emptyMap(),
)
