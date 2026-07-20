package org.helix.node.control.auth

/**
 * An active web-panel session bound to a signed-in Minecraft player.
 *
 * @property name canonical Minecraft name of the signed-in player.
 * @property uuid player uuid, if known at sign-in.
 * @property expiresAtMs epoch millis after which the session is invalid.
 */
data class PanelSession(val name: String, val uuid: String?, val expiresAtMs: Long)
