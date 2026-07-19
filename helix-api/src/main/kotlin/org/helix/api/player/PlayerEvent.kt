package org.helix.api.player

import kotlinx.serialization.Serializable

/**
 * A join or leave reported by a proxy bridge.
 *
 * @property type `join` or `leave`.
 * @property name player name.
 * @property uuid player uuid, if known.
 * @property proxyServiceId reporting proxy service.
 */
@Serializable
data class PlayerEvent(
    val type: String,
    val name: String,
    val uuid: String? = null,
    val proxyServiceId: String = "",
)
