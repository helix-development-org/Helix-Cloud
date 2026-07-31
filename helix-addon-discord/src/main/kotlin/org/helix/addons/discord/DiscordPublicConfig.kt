package org.helix.addons.discord

import kotlinx.serialization.Serializable

/**
 * Bot configuration exposed to the dashboard with the token masked.
 *
 * @property guildId configured guild id.
 * @property panelChannelId control-panel channel id.
 * @property statusChannelId status-board channel id.
 * @property auditChannelId default audit channel id.
 * @property notificationChannelId default notification channel id.
 * @property notificationCategories forwarded notification categories.
 * @property categoryChannels per-category channel overrides.
 * @property auditChannels per-audit-event-type channel overrides.
 * @property statusIntervalSeconds status-board refresh interval.
 * @property linkCodeTtlSeconds lifetime of account-link codes.
 * @property confirmTimeoutSeconds lifetime of pending confirmations.
 * @property criticalActions actions in the type-to-confirm tier.
 * @property destructiveActions actions forced into the button-confirm tier.
 * @property normalActions actions forced into the confirmation-free tier.
 * @property tokenSet whether a bot token is stored.
 * @property connected whether the bot is currently connected.
 * @property links number of confirmed account links.
 */
@Serializable
data class DiscordPublicConfig(
    val guildId: String,
    val panelChannelId: String,
    val statusChannelId: String,
    val auditChannelId: String,
    val notificationChannelId: String,
    val notificationCategories: List<String>,
    val categoryChannels: Map<String, String>,
    val auditChannels: Map<String, String>,
    val statusIntervalSeconds: Int,
    val linkCodeTtlSeconds: Int,
    val confirmTimeoutSeconds: Int,
    val criticalActions: List<String>,
    val destructiveActions: List<String>,
    val normalActions: List<String>,
    val tokenSet: Boolean,
    val connected: Boolean,
    val links: Int,
) {
    companion object {
        /**
         * Builds the public view of a configuration.
         *
         * @param config the full configuration.
         * @param connected whether the bot is connected.
         * @param links number of confirmed account links.
         * @return the dashboard-safe view.
         */
        fun of(config: DiscordConfig, connected: Boolean, links: Int): DiscordPublicConfig =
            DiscordPublicConfig(
                guildId = config.guildId,
                panelChannelId = config.panelChannelId,
                statusChannelId = config.statusChannelId,
                auditChannelId = config.auditChannelId,
                notificationChannelId = config.notificationChannelId,
                notificationCategories = config.notificationCategories,
                categoryChannels = config.categoryChannels,
                auditChannels = config.auditChannels,
                statusIntervalSeconds = config.statusIntervalSeconds,
                linkCodeTtlSeconds = config.linkCodeTtlSeconds,
                confirmTimeoutSeconds = config.confirmTimeoutSeconds,
                criticalActions = config.criticalActions,
                destructiveActions = config.destructiveActions,
                normalActions = config.normalActions,
                tokenSet = config.botToken.isNotBlank(),
                connected = connected,
                links = links,
            )
    }
}
