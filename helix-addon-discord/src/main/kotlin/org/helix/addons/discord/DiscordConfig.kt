package org.helix.addons.discord

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Configuration of the Discord bot, persisted through the addon's document
 * storage under the `discord` key.
 *
 * @property botToken Discord bot token; blank keeps the bot idle.
 * @property channelId channel the bot listens in and posts to.
 * @property commandPrefix prefix of bot commands, for example `!`.
 * @property notificationCategories notification-bus categories forwarded
 *   to the channel.
 * @property adminUserIds Discord user ids allowed to use `!run`.
 * @property allowedActions action names `!run` may invoke, opt-in and empty
 *   by default — a Discord channel must never reach the full action
 *   registry (including administrative actions) just because a user id is
 *   in [adminUserIds].
 */
@Serializable
data class DiscordConfig(
    val botToken: String = "",
    val channelId: String = "",
    val commandPrefix: String = "!",
    val notificationCategories: List<String> = listOf("moderation"),
    val adminUserIds: List<String> = emptyList(),
    val allowedActions: List<String> = emptyList(),
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

        /** Storage document key holding the configuration. */
        private const val DOCUMENT = "discord"

        /**
         * Loads the configuration, writing defaults on first use.
         *
         * @param storage addon-scoped document store.
         * @return the effective configuration.
         */
        fun load(storage: AddonStorage): DiscordConfig {
            val raw = storage.read(DOCUMENT)
            if (raw == null) {
                val defaults = DiscordConfig()
                storage.write(DOCUMENT, json.encodeToString(defaults))
                return defaults
            }
            return json.decodeFromString(raw)
        }

        /**
         * Persists the configuration.
         *
         * @param storage addon-scoped document store.
         * @param config configuration to persist.
         */
        fun save(storage: AddonStorage, config: DiscordConfig) {
            storage.write(DOCUMENT, json.encodeToString(config))
        }
    }
}
