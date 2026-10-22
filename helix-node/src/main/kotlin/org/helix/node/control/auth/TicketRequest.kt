package org.helix.node.control.auth

import kotlinx.serialization.Serializable

/**
 * Request body of `POST /auth/ticket`.
 *
 * @property ticket the one-time ticket handed to the player's client in-game.
 */
@Serializable
data class TicketRequest(val ticket: String)

/**
 * Request body of `POST /internal/panel-ticket` (Paper component asking for a player's ticket).
 *
 * @property player Minecraft name of the online player who opened the in-game panel.
 */
@Serializable
data class TicketIssueRequest(val player: String)

/**
 * Response of `POST /internal/panel-ticket`.
 *
 * @property ticket the one-time ticket; redeem it via `POST /auth/ticket`.
 * @property expiresInSeconds seconds until the ticket expires unredeemed.
 */
@Serializable
data class TicketResponse(val ticket: String, val expiresInSeconds: Long)
