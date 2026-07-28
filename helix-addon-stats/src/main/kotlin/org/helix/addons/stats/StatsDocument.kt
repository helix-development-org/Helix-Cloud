package org.helix.addons.stats

import kotlinx.serialization.Serializable

/**
 * Persisted live stat values.
 *
 * @property stats stat key (lowercase) to player name (lowercase) to current value.
 */
@Serializable
data class StatsDocument(
    val stats: Map<String, Map<String, Long>> = emptyMap(),
)
