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
        config = DiscordConfig.load(configFile())
        handler = DiscordCommandHandler(context.actions) { config }
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
                "config: ${configFile()}",
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
            config = DiscordConfig.load(configFile())
            startBot()
            ActionResult.ok("reloaded — configured: ${config.configured()}")
        }
        startBot()
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

    private fun configFile() = context.dataDirectory.resolve("discord.json")
}
