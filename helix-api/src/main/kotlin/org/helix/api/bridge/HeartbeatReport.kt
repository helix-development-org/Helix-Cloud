package org.helix.api.bridge

import kotlinx.serialization.Serializable

/**
 * Periodic status report a bridge sends to the node.
 *
 * The first heartbeat moves a service from `STARTING` to `RUNNING`.
 *
 * @property serviceId id of the reporting service.
 * @property onlinePlayers players currently connected.
 * @property maxPlayers player slots the server offers.
 * @property tps ticks per second, if the platform exposes them.
 */
@Serializable
data class HeartbeatReport(
    val serviceId: String,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val tps: Double? = null,
)
