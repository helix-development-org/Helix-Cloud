package org.helix.addons.cinematic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings of the AFK handling, published to the Paper component as bridge value `cinematic.config`.
 *
 * @property mode what idle players get: `screen` (dimmed "you are AFK" screen, default) or `cinematic` (camera).
 * @property tasks tasks the camera is active on (default: the lobby).
 * @property afkSeconds seconds without any input before the camera starts.
 * @property shotSeconds length of the longest fly-in (shorter ones are random, from 3 s).
 * @property fade whether shots are separated by a fade to black.
 * @property nameCard whether the first shot additionally shows the player's name as subtitle.
 * @property afkTag MiniMessage text shown above AFK players to others (blank: none).
 * @property afkTitle MiniMessage headline of the AFK screen.
 * @property afkSubtitle MiniMessage hint below it.
 */
@Serializable
data class CinematicConfig(
    val mode: String = "screen",
    val tasks: List<String> = listOf("Lobby"),
    val afkSeconds: Int = 30,
    val shotSeconds: Int = 8,
    val fade: Boolean = true,
    val nameCard: Boolean = false,
    val afkTag: String = "<gray>AFK",
    val afkTitle: String = "<white><bold>Du bist AFK",
    val afkSubtitle: String = "<gray>Klicke oder bewege dich, um weiterzuspielen",
) {
    /**
     * A copy with [key] set from text (`tasks` comma separated), validated.
     *
     * @param key setting name.
     * @param value new value as text.
     * @return the changed config.
     * @throws IllegalArgumentException for an unknown key or invalid value.
     */
    fun with(key: String, value: String): CinematicConfig = when (key) {
        "mode" -> copy(mode = value.lowercase().takeIf { it in MODES } ?: invalid(key, MODES.joinToString("|")))
        "afkTitle" -> copy(afkTitle = value)
        "afkSubtitle" -> copy(afkSubtitle = value)
        "tasks" -> copy(tasks = value.split(',', ' ').map(String::trim).filter(String::isNotEmpty))
        "afkSeconds" -> copy(afkSeconds = value.toIntOrNull()?.takeIf { it in 5..3600 } ?: invalid(key, "5..3600"))
        "shotSeconds" -> copy(shotSeconds = value.toIntOrNull()?.takeIf { it in 3..30 } ?: invalid(key, "3..30"))
        "fade" -> copy(fade = value.toBooleanStrictOrNull() ?: invalid(key, "true|false"))
        "nameCard" -> copy(nameCard = value.toBooleanStrictOrNull() ?: invalid(key, "true|false"))
        "afkTag" -> copy(afkTag = value)
        else -> throw IllegalArgumentException("unknown setting $key (${KEYS.joinToString()})")
    }

    /** @return this config as JSON. */
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    /** Parsing and the list of settings. */
    companion object {
        /** All setting names. */
        val KEYS = listOf("mode", "tasks", "afkSeconds", "afkTitle", "afkSubtitle", "afkTag", "shotSeconds", "fade", "nameCard")

        /** Valid modes. */
        val MODES = listOf("screen", "cinematic")

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * @param json stored JSON, or null.
         * @return the parsed config, defaults when missing or broken.
         */
        fun parse(json: String?): CinematicConfig =
            json?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: CinematicConfig()

        private fun invalid(key: String, range: String): Nothing = throw IllegalArgumentException("$key must be $range")
    }
}
