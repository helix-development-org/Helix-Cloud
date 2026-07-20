package org.helix.node.control.auth

import kotlinx.serialization.Serializable

/**
 * Response of `POST /auth/request-code`.
 *
 * @property delivered whether a code was sent to the player in-game.
 * @property message human-readable status for the login screen.
 */
@Serializable
data class ChallengeResponse(val delivered: Boolean, val message: String)
