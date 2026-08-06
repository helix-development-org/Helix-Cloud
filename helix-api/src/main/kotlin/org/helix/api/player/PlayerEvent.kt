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
 * @property address the joining player's IP address as reported by the
 *  proxy, only on join. The node salts and hashes it immediately for the
 *  shared-address registry; the raw address is never persisted.
 */
@Serializable
data class PlayerEvent(
    val type: String,
    val name: String,
    val uuid: String? = null,
    val proxyServiceId: String = "",
    val permissions: List<String> = emptyList(),
    val address: String? = null,
)
