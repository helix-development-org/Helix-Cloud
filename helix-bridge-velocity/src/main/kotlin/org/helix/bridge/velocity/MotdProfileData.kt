package org.helix.bridge.velocity

import kotlinx.serialization.Serializable

/**
 * One server-list appearance as published by the MOTD addon.
 *
 * @property line1 first MOTD line (MiniMessage/`&` codes, placeholders).
 * @property line2 second MOTD line.
 * @property maxPlayers shown max player count; `-1` keeps the real value.
 * @property onlinePlayers shown online count; `-1` keeps the real value.
 * @property versionText replacement version name; empty keeps the default.
 * @property hover lines shown when hovering the player count.
 */
@Serializable
data class MotdProfileData(
    val line1: String = "",
    val line2: String = "",
    val maxPlayers: Int = -1,
    val onlinePlayers: Int = -1,
    val versionText: String = "",
    val hover: List<String> = emptyList(),
)
