package org.helix.wire

import kotlinx.serialization.Serializable

/**
 * Wire request carrying only a player name, for name-scoped lookups that
 * have no dedicated DTO (the HTTP side passes it as a query parameter).
 *
 * @property name the player name.
 */
@Serializable
data class PlayerName(val name: String)

/**
 * Wire request acknowledging proxy commands up to a sequence number,
 * mirroring the HTTP long-poll's `ackUpTo` cursor.
 *
 * @property ackUpTo highest command sequence the proxy has applied.
 */
@Serializable
data class PollAck(val ackUpTo: Long)

/**
 * Wire response wrapping a raw JSON string produced by an addon (the ban
 * snapshot), kept verbatim so the node stays agnostic of its shape.
 *
 * @property json the raw JSON text.
 */
@Serializable
data class RawJson(val json: String)
