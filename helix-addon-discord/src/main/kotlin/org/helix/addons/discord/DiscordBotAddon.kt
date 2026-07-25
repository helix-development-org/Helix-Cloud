package org.helix.addons.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Discord bot addon built on Kord.
 *
 * Connects to the Discord gateway with the configured bot token, forwards
 * notification-bus events (bans, warns, kicks) into the configured channel
 * and answers `!status`, `!players`, `!help` and the admin-gated
 * `!run <action>` — every reply comes straight from the platform's action
 * contract.
 *
 * Configuration lives in `Helix/addons/data/helix.discord/discord.json`;
 * without a token the addon stays idle until `discord.reload`.
 */
class DiscordBotAddon : AddonBase() {
    @Volatile
    private var config: DiscordConfig = DiscordConfig()
    private lateinit var handler: DiscordCommandHandler
    private val kordRef = AtomicReference<Kord?>(null)
    private var scope: CoroutineScope? = null

    /**
     * Loads the configuration, registers actions and starts the bot when
     * configured.
     */
    override fun enable() {
        config = DiscordConfig.load(context.storage())
        val msg = context.localizedMessages(DiscordCommandHandler.DEFAULT_MESSAGES)
        handler = DiscordCommandHandler(context.actions, { config }, msg)
        context.registerNotificationListener { category, message ->
            if (category in config.notificationCategories) {
                sendToChannel(handler.stripColors(message))
            }
        }
        action("discord.status", "Shows the Discord bot state.", "discord.status") {
            ActionResult.ok(
                "configured: ${config.configured()}",
                "connected: ${kordRef.get() != null}",
                "channel: ${config.channelId.ifBlank { "-" }}",
                "notification categories: ${config.notificationCategories.joinToString()}",
                "storage document: discord",
            )
        }
        action("discord.send", "Sends a message to the Discord channel.", "discord.send <text...>") { invocation ->
            val text = invocation.arguments.joinToString(" ")
            when {
                text.isBlank() -> ActionResult.error("usage: discord.send <text...>")
                kordRef.get() == null -> ActionResult.error("discord bot is not connected")
                else -> {
                    sendToChannel(handler.stripColors(text))
                    ActionResult.ok("sent")
                }
            }
        }
        action("discord.reload", "Reloads discord.json and reconnects the bot.", "discord.reload") {
            stopBot()
            config = DiscordConfig.load(context.storage())
            startBot()
            ActionResult.ok("reloaded — configured: ${config.configured()}")
        }
        action("discord.config.get", "Exports the bot configuration as JSON (token masked).", "discord.config.get") {
            val public = DiscordPublicConfig(
                channelId = config.channelId,
                commandPrefix = config.commandPrefix,
                notificationCategories = config.notificationCategories,
                adminUserIds = config.adminUserIds,
                tokenSet = config.botToken.isNotBlank(),
                connected = kordRef.get() != null,
            )
            ActionResult.ok(Json.encodeToString(public))
        }
        action(
            "discord.config.set",
            "Updates the bot config and reconnects. Keys: token, channel, prefix, categories, admins.",
            "discord.config.set <key=value>...",
        ) { invocation -> updateConfig(invocation) }
        panel(
            "discord",
            "Discord",
            "/panel.html",
            "<path d=\"M8 12a1 1 0 100-2 1 1 0 000 2zM16 12a1 1 0 100-2 1 1 0 000 2z\"/>" +
                "<path d=\"M7 5.5A16 16 0 0117 5.5C19 9 19.5 13 19 17a13 13 0 01-4 2l-1-2M9 17l-1 2a13 13 0 01-4-2c-.5-4 0-8 2-11.5\"/>",
        )
        startBot()
    }

    private fun updateConfig(invocation: org.helix.api.action.ActionInvocation): ActionResult {
        val overrides = invocation.arguments.mapNotNull { arg ->
            val parts = arg.split("=", limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1] else null
        }.toMap()
        /** Parses a comma-separated override into a clean list, or null. */
        fun list(key: String) = overrides[key]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val current = DiscordConfig.load(context.storage())
        val updated = current.copy(
            botToken = overrides["token"]?.takeIf { it.isNotBlank() } ?: current.botToken,
            channelId = overrides["channel"] ?: current.channelId,
            commandPrefix = overrides["prefix"] ?: current.commandPrefix,
            notificationCategories = list("categories") ?: current.notificationCategories,
            adminUserIds = list("admins") ?: current.adminUserIds,
        )
        DiscordConfig.save(context.storage(), updated)
        stopBot()
        config = updated
        startBot()
        return ActionResult.ok("configuration saved — configured: ${updated.configured()}")
    }

    /**
     * Logs the bot out and stops the coroutine scope.
     */
    override fun onDisable() {
        stopBot()
    }

    private fun startBot() {
        if (!config.configured()) {
            return
        }
        val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = botScope
        botScope.launch {
            runCatching { runBot() }
                .onFailure { publishState("Discord bot stopped: ${it.message}") }
        }
    }

    @OptIn(PrivilegedIntent::class)
    private suspend fun runBot() {
        val bot = Kord(config.botToken)
        kordRef.set(bot)
        bot.on<MessageCreateEvent> {
            val reply = handler.handle(
                authorId = message.author?.id?.toString() ?: "",
                authorIsBot = message.author?.isBot ?: true,
                channelId = message.channelId.toString(),
                content = message.content,
            )
            if (reply != null) {
                message.channel.createMessage(reply)
            }
        }
        publishState("Discord bot connecting to channel ${config.channelId}")
        bot.login {
            intents = Intents(Intent.Guilds, Intent.GuildMessages, Intent.MessageContent)
        }
    }

    private fun stopBot() {
        val bot = kordRef.getAndSet(null)
        if (bot != null) {
            runCatching { runBlocking { bot.logout() } }
        }
        scope?.cancel()
        scope = null
    }

    private fun sendToChannel(text: String) {
        val bot = kordRef.get() ?: return
        val channel = runCatching { Snowflake(config.channelId) }.getOrNull() ?: return
        scope?.launch {
            runCatching {
                bot.rest.channel.createMessage(channel) { content = text }
            }
        }
    }

    private fun publishState(message: String) {
        context.publishNotification("discord", message)
    }

}
