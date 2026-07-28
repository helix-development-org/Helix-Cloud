package org.helix.api.player

import kotlinx.serialization.Serializable

/**
 * A proxy bridge's complete current player list, used to reconcile the
 * node's registry after an outage — joins/leaves missed while the node was
 * unreachable would otherwise desync [PlayerRegistry] until each affected
 * player manually reconnects.
 *
 * @property proxyServiceId reporting proxy service.
 * @property players every player currently connected through that proxy.
 */
@Serializable
data class PlayerRosterReport(
    val proxyServiceId: String,
    val players: List<RosterPlayer>,
)

/**
 * One player entry in a [PlayerRosterReport].
 *
 * @property name player name.
 * @property uuid player uuid, if known.
 */
@Serializable
data class RosterPlayer(
    val name: String,
    val uuid: String? = null,
)
