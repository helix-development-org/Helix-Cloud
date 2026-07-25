package org.helix.addons.bettermsgs

import kotlinx.serialization.Serializable

/**
 * A single private message inside a conversation.
 *
 * Also used verbatim inside the `bettermsgs.history` JSON response, so the
 * serialized field names are part of the control-API contract.
 *
 * @property from sender name, lowercase.
 * @property text raw message text.
 * @property epochMs server timestamp of the append, epoch milliseconds.
 */
@Serializable
data class ChatMessage(
    val from: String,
    val text: String,
    val epochMs: Long,
)

/**
 * One peer entry in a player's contact index.
 *
 * @property lastEpochMs timestamp of the newest message exchanged with the
 *   peer, epoch milliseconds.
 * @property unread number of messages from the peer the player has not
 *   read yet.
 */
@Serializable
data class ContactEntry(
    val lastEpochMs: Long,
    val unread: Int = 0,
)
