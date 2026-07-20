package org.helix.node.control.auth

import kotlinx.serialization.Serializable

/**
 * Request body of `POST /auth/request-code`.
 *
 * @property name the Minecraft name the player wants to sign in with.
 */
@Serializable
data class LoginRequest(val name: String)
