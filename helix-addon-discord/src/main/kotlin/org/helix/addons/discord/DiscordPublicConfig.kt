package org.helix.addons.discord

import kotlinx.serialization.Serializable

/**
 * Bot configuration exposed to the dashboard with the token masked.
 *
 * @property channelId configured channel id.
 * @property commandPrefix command prefix.
 * @property notificationCategories forwarded notification categories.
 * @property adminUserIds ids allowed to use `!run`.
 * @property tokenSet whether a bot token is stored.
 * @property connected whether the bot is currently connected.
 */
@Serializable
data class DiscordPublicConfig(
    val channelId: String,
    val commandPrefix: String,
    val notificationCategories: List<String>,
    val adminUserIds: List<String>,
    val tokenSet: Boolean,
    val connected: Boolean,
)
