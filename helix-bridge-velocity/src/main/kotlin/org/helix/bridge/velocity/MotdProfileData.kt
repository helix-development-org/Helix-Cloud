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
 * @property frames animation frames; empty means the single frame line1/line2.
 * @property frameIntervalMs milliseconds between animation frames.
 */
@Serializable
data class MotdProfileData(
    val line1: String = "",
    val line2: String = "",
    val maxPlayers: Int = -1,
    val onlinePlayers: Int = -1,
    val versionText: String = "",
    val hover: List<String> = emptyList(),
    val frames: List<MotdFrameData> = emptyList(),
    val frameIntervalMs: Long = 3000,
) {
    /**
     * The frame active at the given time (time-based rotation).
     *
     * @param nowEpochMs current epoch millis.
     * @return the active frame; falls back to the base lines.
     */
    fun frameAt(nowEpochMs: Long): MotdFrameData {
        val all = frames.ifEmpty { return MotdFrameData(line1, line2) }
        val interval = frameIntervalMs.coerceAtLeast(1)
        val index = ((nowEpochMs / interval) % all.size).toInt()
        return all[index]
    }
}
