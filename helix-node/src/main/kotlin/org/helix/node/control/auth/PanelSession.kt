package org.helix.node.control.auth

/**
 * An active web-panel session bound to a signed-in Minecraft player.
 *
 * @property name canonical Minecraft name of the signed-in player.
 * @property uuid player uuid, if known at sign-in.
 * @property expiresAtMs epoch millis after which the session is invalid
 *  regardless of activity (the absolute TTL).
 * @property lastSeenAtMs epoch millis of the last request authenticated with
 *  this session; the session also expires when idle past the idle TTL.
 */
data class PanelSession(val name: String, val uuid: String?, val expiresAtMs: Long, val lastSeenAtMs: Long)
