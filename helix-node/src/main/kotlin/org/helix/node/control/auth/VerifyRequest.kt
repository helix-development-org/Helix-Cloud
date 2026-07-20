package org.helix.node.control.auth

import kotlinx.serialization.Serializable

/**
 * Request body of `POST /auth/verify`.
 *
 * @property name the Minecraft name being verified.
 * @property code the code the player received in-game.
 */
@Serializable
data class VerifyRequest(val name: String, val code: String)
