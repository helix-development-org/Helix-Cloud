package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * The full set of permission nodes a player currently holds, resolved
 * against every known permission node in the network catalog.
 *
 * Used by backend bridges (for example the Paper bridge) to mirror the
 * node's permission decisions onto the Minecraft-native permission system,
 * so third-party plugins calling `Player#hasPermission` transparently see
 * this network's own decisions.
 *
 * @property name player name.
 * @property granted the permission nodes the player is granted.
 */
@Serializable
data class PlayerPermissionsSnapshot(
    val name: String,
    val granted: List<String>,
)
