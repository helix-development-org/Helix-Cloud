package org.helix.addons.cinematic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings of the cinematic AFK camera, published to the Paper component as bridge value `cinematic.config`.
 *
 * @property tasks tasks the camera is active on (default: the lobby).
 * @property afkSeconds seconds without any input before the camera starts.
 * @property shotSeconds length of one drone shot.
 * @property fade whether shots are separated by a fade to black.
 * @property nameCard whether the first shot shows the player's name.
 * @property afkTag MiniMessage text shown above AFK players to others (blank: none).
 */
@Serializable
data class CinematicConfig(
    val tasks: List<String> = listOf("Lobby"),
    val afkSeconds: Int = 30,
    val shotSeconds: Int = 8,
    val fade: Boolean = true,
    val nameCard: Boolean = true,
    val afkTag: String = "<gray>AFK",
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
        val KEYS = listOf("tasks", "afkSeconds", "shotSeconds", "fade", "nameCard", "afkTag")

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
