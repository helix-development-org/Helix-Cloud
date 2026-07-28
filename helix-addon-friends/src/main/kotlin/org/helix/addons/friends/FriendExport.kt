package org.helix.addons.friends

import kotlinx.serialization.Serializable

/**
 * A player's friend data, shaped for a GDPR export.
 *
 * @property friends the player's current friends.
 * @property incomingRequests pending requests sent to the player.
 */
@Serializable
data class FriendExport(
    val friends: List<String> = emptyList(),
    val incomingRequests: List<String> = emptyList(),
)
