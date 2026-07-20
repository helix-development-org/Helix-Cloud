package org.helix.node.control.auth

import kotlinx.serialization.Serializable

/**
 * Response of `POST /auth/verify` on success: the bearer token to use for all
 * subsequent API calls plus the caller's identity.
 *
 * @property token session bearer token to send as `Authorization: Bearer`.
 * @property identity the signed-in caller and their allowed views.
 */
@Serializable
data class SessionResponse(val token: String, val identity: PanelIdentity)
