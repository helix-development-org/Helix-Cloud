package org.helix.api.player

import kotlinx.serialization.Serializable

/**
 * A join or leave reported by a proxy bridge.
 *
 * @property type `join` or `leave`.
 * @property name player name.
 * @property uuid player uuid, if known.
 * @property proxyServiceId reporting proxy service.
 * @property permissions Minecraft-native permission nodes the player holds, as
 *  evaluated by the bridge on join (used as the default permission source when
 *  no permission addon is active); empty on leave.
 */
@Serializable
data class PlayerEvent(
    val type: String,
    val name: String,
    val uuid: String? = null,
    val proxyServiceId: String = "",
    val permissions: List<String> = emptyList(),
)
