package org.helix.node.control.auth

/**
 * A login code delivered in-game and awaiting verification.
 *
 * @property name canonical Minecraft name the code was issued for.
 * @property uuid player uuid, if known when the code was issued.
 * @property code the numeric code sent to the player.
 * @property expiresAtMs epoch millis after which the code is rejected.
 * @property attempts number of failed verification attempts so far.
 */
data class PendingLogin(
    val name: String,
    val uuid: String?,
    val code: String,
    val expiresAtMs: Long,
    val attempts: Int = 0,
)
