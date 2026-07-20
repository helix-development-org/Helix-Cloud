package org.helix.addons.discord

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Configuration of the Discord bot, persisted as `discord.json` in the
 * addon data directory.
 *
 * @property botToken Discord bot token; blank keeps the bot idle.
 * @property channelId channel the bot listens in and posts to.
 * @property commandPrefix prefix of bot commands, for example `!`.
 * @property notificationCategories notification-bus categories forwarded
 *   to the channel.
 * @property adminUserIds Discord user ids allowed to use `!run`.
 */
@Serializable
data class DiscordConfig(
    val botToken: String = "",
    val channelId: String = "",
    val commandPrefix: String = "!",
    val notificationCategories: List<String> = listOf("moderation"),
    val adminUserIds: List<String> = emptyList(),
) {
    /**
     * Whether token and channel are configured.
     *
     * @return `true` when the bot can connect.
     */
    fun configured(): Boolean = botToken.isNotBlank() && channelId.isNotBlank()

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Loads the configuration, writing defaults on first use.
         *
         * @param file path of `discord.json`.
         * @return the effective configuration.
         */
        fun load(file: Path): DiscordConfig {
            if (Files.notExists(file)) {
                val defaults = DiscordConfig()
                Files.createDirectories(file.parent)
                Files.writeString(file, json.encodeToString(defaults))
                return defaults
            }
            return json.decodeFromString(Files.readString(file))
        }

        /**
         * Writes the configuration to disk.
         *
         * @param file path of `discord.json`.
         * @param config configuration to persist.
         */
        fun save(file: Path, config: DiscordConfig) {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(config))
        }
    }
}
