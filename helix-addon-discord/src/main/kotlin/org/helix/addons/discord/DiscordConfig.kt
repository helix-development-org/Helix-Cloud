package org.helix.addons.discord

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.api.storage.AddonStorage

/**
 * Configuration of the Discord bot, persisted through the addon's document
 * storage under the `discord` key.
 *
 * Channel routing is fully configurable: every notification category and
 * every audit event type may point at its own channel through
 * [categoryChannels] and [auditChannels], falling back to
 * [notificationChannelId] and [auditChannelId]. A blank effective channel
 * drops the event.
 *
 * @property botToken Discord bot token; blank keeps the bot idle.
 * @property guildId the guild (server) the bot manages; commands are
 *   registered as guild commands there.
 * @property panelChannelId channel holding the persistent control panel.
 * @property statusChannelId channel holding the live status board.
 * @property auditChannelId default channel for audit events.
 * @property notificationChannelId default channel for notification-bus
 *   categories.
 * @property notificationCategories notification-bus categories forwarded to
 *   Discord.
 * @property categoryChannels per-category channel overrides.
 * @property auditChannels per-audit-event-type channel overrides; known
 *   types are `action`, `denied`, `confirmation` and `link`.
 * @property statusIntervalSeconds interval of the status-board refresh.
 * @property linkCodeTtlSeconds lifetime of account-link codes.
 * @property confirmTimeoutSeconds lifetime of pending confirmations.
 * @property normalActions actions forced into the confirmation-free tier.
 * @property destructiveActions actions forced into the button-confirm tier.
 * @property criticalActions actions forced into the type-to-confirm tier.
 */
@Serializable
data class DiscordConfig(
    val botToken: String = "",
    val guildId: String = "",
    val panelChannelId: String = "",
    val statusChannelId: String = "",
    val auditChannelId: String = "",
    val notificationChannelId: String = "",
    val notificationCategories: List<String> = listOf("moderation"),
    val categoryChannels: Map<String, String> = emptyMap(),
    val auditChannels: Map<String, String> = emptyMap(),
    val statusIntervalSeconds: Int = 60,
    val linkCodeTtlSeconds: Int = 300,
    val confirmTimeoutSeconds: Int = 30,
    val normalActions: List<String> = emptyList(),
    val destructiveActions: List<String> = emptyList(),
    val criticalActions: List<String> = DEFAULT_CRITICAL,
) {
    /**
     * Whether token and guild are configured.
     *
     * @return `true` when the bot can connect.
     */
    fun configured(): Boolean = botToken.isNotBlank() && guildId.isNotBlank()

    /**
     * The channel a notification category routes to.
     *
     * @param category the notification category.
     * @return the channel id, or blank when the category is dropped.
     */
    fun channelForCategory(category: String): String =
        categoryChannels[category]?.ifBlank { null } ?: notificationChannelId

    /**
     * The channel an audit event type routes to.
     *
     * @param type audit event type, for example `action` or `link`.
     * @return the channel id, or blank when the event type is dropped.
     */
    fun channelForAudit(type: String): String =
        auditChannels[type]?.ifBlank { null } ?: auditChannelId

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Storage document key holding the configuration. */
        private const val DOCUMENT = "discord"

        /** Actions defaulting to the type-to-confirm tier. */
        val DEFAULT_CRITICAL = listOf(
            "platform.stop",
            "platform.restart",
            "launcher.restart",
            "task.delete",
            "service.command",
            "player.gdpr-delete",
        )

        /**
         * Loads the configuration, writing defaults on first use.
         *
         * A pre-0.82 document (single `channelId`, prefix commands) is
         * migrated by carrying its channel over as the notification
         * channel; the removed prefix/allowlist fields are dropped.
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
            val decoded = json.decodeFromString<DiscordConfig>(raw)
            val legacyChannel = runCatching {
                json.parseToJsonElement(raw).jsonObject["channelId"]?.jsonPrimitive?.content
            }.getOrNull()
            if (!legacyChannel.isNullOrBlank() && decoded.notificationChannelId.isBlank()) {
                val migrated = decoded.copy(notificationChannelId = legacyChannel)
                save(storage, migrated)
                return migrated
            }
            return decoded
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
