package org.helix.api.player

import kotlinx.serialization.Serializable

/**
 * A player currently connected to the network.
 *
 * @property name player name.
 * @property uuid player uuid, if known.
 * @property proxyServiceId proxy service the player is connected through.
 * @property joinedAtEpochMs epoch millis of the join.
 */
@Serializable
data class OnlinePlayer(
    val name: String,
    val uuid: String? = null,
    val proxyServiceId: String = "",
    val joinedAtEpochMs: Long = 0,
)
