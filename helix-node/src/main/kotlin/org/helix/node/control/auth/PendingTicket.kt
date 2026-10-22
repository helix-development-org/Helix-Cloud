package org.helix.node.control.auth

/**
 * A one-time login ticket issued for an online player (in-game admin panel), awaiting redemption.
 *
 * @property name canonical Minecraft name the ticket was issued for.
 * @property uuid player uuid, if known.
 * @property expiresAtMs epoch millis after which the ticket is rejected.
 */
data class PendingTicket(
    val name: String,
    val uuid: String?,
    val expiresAtMs: Long,
)
