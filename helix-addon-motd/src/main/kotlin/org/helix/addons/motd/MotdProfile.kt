package org.helix.addons.motd

import kotlinx.serialization.Serializable

/**
 * One complete server-list appearance.
 *
 * Text fields support MiniMessage tags and legacy `&` codes plus the
 * placeholders `{online}`, `{max}` and `{network}`.
 *
 * @property line1 first MOTD line.
 * @property line2 second MOTD line.
 * @property maxPlayers shown max player count; `-1` shows the real value.
 * @property onlinePlayers shown online count; `-1` shows the real value.
 * @property versionText text shown instead of the version name; empty keeps
 *  the proxy default.
 * @property hover lines shown when hovering the player count.
 * @property frames animation frames for the two lines; empty means the single
 *  frame `[line1/line2]`. With more than one frame the server-list entry is
 *  animated: each ping shows the frame active at that moment.
 * @property frameIntervalMs milliseconds between animation frames.
 */
@Serializable
data class MotdProfile(
    val line1: String = "",
    val line2: String = "",
    val maxPlayers: Int = -1,
    val onlinePlayers: Int = -1,
    val versionText: String = "",
    val hover: List<String> = emptyList(),
    val frames: List<MotdFrame> = emptyList(),
    val frameIntervalMs: Long = 3000,
) {
    /**
     * Effective animation frames (falls back to the base lines).
     *
     * @return at least one frame.
     */
    fun effectiveFrames(): List<MotdFrame> = frames.ifEmpty { listOf(MotdFrame(line1, line2)) }
}
