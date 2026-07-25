package org.helix.addons.bettermsgs

import kotlinx.serialization.Serializable

/**
 * JSON payload returned by `bettermsgs.send`.
 *
 * @property ok always `true` on success.
 * @property epochMs server timestamp assigned to the appended message.
 */
@Serializable
data class SendResponse(
    val ok: Boolean,
    val epochMs: Long,
)

/**
 * JSON payload returned by `bettermsgs.history`.
 *
 * @property total total number of messages stored for the pair.
 * @property offset the requested offset, counted back from the newest
 *   message.
 * @property messages the windowed messages, oldest first.
 */
@Serializable
data class HistoryResponse(
    val total: Int,
    val offset: Int,
    val messages: List<ChatMessage>,
)

/**
 * One element of the JSON array returned by `bettermsgs.contacts`.
 *
 * @property name peer name, lowercase.
 * @property lastEpochMs timestamp of the newest message exchanged with the
 *   peer, epoch milliseconds.
 * @property unread number of unread messages from the peer.
 * @property online whether the peer is currently connected to the network.
 */
@Serializable
data class ContactView(
    val name: String,
    val lastEpochMs: Long,
    val unread: Int,
    val online: Boolean,
)

/**
 * Plain acknowledgement JSON payload returned by `bettermsgs.read` and
 * `bettermsgs.focus`.
 *
 * @property ok always `true` on success.
 */
@Serializable
data class OkResponse(
    val ok: Boolean,
)
