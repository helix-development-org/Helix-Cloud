package org.helix.api.display

import kotlinx.serialization.Serializable

/**
 * A batched display-profile lookup for every currently online player on one
 * backend, so a bridge covers a full refresh cycle with one HTTP call
 * instead of one call per player.
 *
 * @property names player names to resolve.
 */
@Serializable
data class DisplayBulkRequest(
    val names: List<String>,
)
