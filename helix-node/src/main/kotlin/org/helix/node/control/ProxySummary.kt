package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * A proxy service in the proxy overview.
 *
 * @property id proxy service id.
 * @property state lifecycle state.
 * @property executor execution backend.
 * @property port listen port.
 * @property onlinePlayers connected players.
 * @property maxPlayers advertised slots.
 */
@Serializable
data class ProxySummary(
    val id: String,
    val state: String,
    val executor: String,
    val port: Int,
    val onlinePlayers: Int,
    val maxPlayers: Int,
)
