package org.helix.addons.cinematic.paper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `cinematic.config` bridge value published by the node addon (pure, testable).
 *
 * @property tasks tasks the camera is active on.
 * @property afkSeconds seconds without input before the camera starts.
 * @property shotSeconds length of the longest fly-in (shorter ones are random, from 3 s).
 * @property fade whether shots are separated by a fade to black.
 * @property nameCard whether the first shot additionally shows the player's name as subtitle.
 * @property afkTag MiniMessage text above AFK players for others (blank: none).
 * @property mode `screen` (AFK screen) or `cinematic` (camera).
 * @property afkTitle MiniMessage headline of the AFK screen.
 * @property afkSubtitle MiniMessage hint below it.
 */
@Serializable
data class CinematicSettings(
    val mode: String = "screen",
    val tasks: List<String> = listOf("Lobby"),
    val afkSeconds: Int = 30,
    val shotSeconds: Int = 8,
    val fade: Boolean = true,
    val nameCard: Boolean = false,
    val afkTag: String = "<#a89a84>AFK",
    val afkTitle: String = "<#ffcf4a><bold>Du bist AFK",
    val afkSubtitle: String = "<#f1e2c2>Klick oder beweg dich, um weiterzuspielen",
) {
    /**
     * @param task this service's task.
     * @return whether the camera runs on it (task names compare case-insensitively).
     */
    fun activeOn(task: String): Boolean = tasks.any { it.equals(task, ignoreCase = true) }

    /** Whether idle players get the cinematic camera instead of the AFK screen. */
    val cinematic: Boolean get() = mode.equals("cinematic", ignoreCase = true)

    /** Parsing of the bridge values. */
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val OPT_OUT_PREFIX = "cinematic.off."

        /**
         * @param bridgeValues all bridge values.
         * @return the settings (defaults if not published).
         */
        fun from(bridgeValues: Map<String, String>): CinematicSettings =
            bridgeValues["cinematic.config"]?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
                ?: CinematicSettings()

        /**
         * @param bridgeValues all bridge values.
         * @return lowercase names of players who turned the camera off in their profile.
         */
        fun optedOut(bridgeValues: Map<String, String>): Set<String> =
            bridgeValues.keys.filter { it.startsWith(OPT_OUT_PREFIX) }.map { it.removePrefix(OPT_OUT_PREFIX) }.toSet()
    }
}
