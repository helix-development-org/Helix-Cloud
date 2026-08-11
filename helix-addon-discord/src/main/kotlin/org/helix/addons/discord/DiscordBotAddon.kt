package org.helix.addons.discord

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.addon.PlayerListener
import org.helix.api.message.Messages
import org.helix.api.player.OnlinePlayer
import org.helix.api.storage.AddonStorage

/**
 * Discord bot addon built on Kord — the network's full admin tooling on
 * Discord.
 *
 * Slash commands (`/helix …`), a persistent Components-V2 control panel
 * with curated modules (services, tasks, players, proxy/platform,
 * permissions, addons), a dynamic action browser over the whole action
 * registry, a live status board and a per-category configurable audit
 * trail of everything humans trigger on the network.
 *
 * Authorization is exclusively account-based: a Discord user links their
 * Minecraft account (codes in both directions, in-game command gated by
 * `helix.discord.link`) and every bot feature checks the linked account's
 * `helix.discord.action.<action>` node — no user-id bypass, no allowlist.
 * Destructive actions need a second click, critical ones a typed
 * confirmation.
 */
class DiscordBotAddon : AddonBase() {
    @Volatile
    private var config: DiscordConfig = DiscordConfig()
    private lateinit var msg: Messages
    private lateinit var links: LinkStore
    private lateinit var runtime: BotRuntime

    /**
     * Loads the configuration, wires the bot services, registers actions
     * and starts the bot when configured.
     */
    override fun enable() {
        val storage = context.storage()
        config = DiscordConfig.load(storage)
        msg = loadMessages()
        val texts = DiscordMessages(msg)
        links = LinkStore(
            storage = storage,
            ttlMs = { config.linkCodeTtlSeconds * 1000L },
        )
        val gate = PermissionGate(
            links = links,
            hasPermission = context::hasPermission,
            currentName = context::lastKnownName,
        )
        val catalog = ActionCatalog(context.actions)
        val audit = AuditLog(
            config = { config },
            texts = texts,
            descriptorOf = catalog::find,
            sink = { channelId, text, accent -> runtime.send(channelId, text, accent) },
        )
        val services = BotServices(
            config = { config },
            saveConfig = { updated ->
                DiscordConfig.save(storage, updated)
                config = updated
            },
            actions = context.actions,
            catalog = catalog,
            links = links,
            gate = gate,
            confirmations = ConfirmationManager(timeoutMs = { config.confirmTimeoutSeconds * 1000L }),
            audit = audit,
            texts = texts,
            onlinePlayers = context::onlinePlayers,
            installedAddons = context::installedAddons,
            resolveUuid = context::resolvePlayerUuid,
            lastKnownName = context::lastKnownName,
        )
        runtime = BotRuntime(services, storage) { state -> context.publishNotification("discord", state) }
        context.registerNotificationListener(runtime::notification)
        context.registerActionObserver(audit::observe)
        context.registerPlayerListener(object : PlayerListener {
            override fun onJoin(player: OnlinePlayer) = runtime.pokeStatus()

            override fun onLeave(player: OnlinePlayer) = runtime.pokeStatus()
        })
        registerBotActions(storage, audit)
        registerLinkCommand(audit)
        panel(
            "discord",
            "Discord",
            "/panel.html",
            "<path d=\"M8 12a1 1 0 100-2 1 1 0 000 2zM16 12a1 1 0 100-2 1 1 0 000 2z\"/>" +
                "<path d=\"M7 5.5A16 16 0 0117 5.5C19 9 19.5 13 19 17a13 13 0 01-4 2l-1-2M9 17l-1 2a13 13 0 01-4-2c-.5-4 0-8 2-11.5\"/>",
        )
        runtime.start()
    }

    /**
     * Stops the bot.
     */
    override fun onDisable() {
        runtime.stop()
    }

    private fun registerBotActions(storage: AddonStorage, audit: AuditLog) {
        action("discord.status", "Shows the Discord bot state.", "discord.status") {
            ActionResult.ok(
                "configured: ${config.configured()}",
                "connected: ${runtime.connected()}",
                "guild: ${config.guildId.ifBlank { "-" }}",
                "channels: panel=${config.panelChannelId.ifBlank { "-" }} " +
                    "status=${config.statusChannelId.ifBlank { "-" }} " +
                    "audit=${config.auditChannelId.ifBlank { "-" }} " +
                    "notify=${config.notificationChannelId.ifBlank { "-" }}",
                "notification categories: ${config.notificationCategories.joinToString()}",
                "links: ${links.all().size}",
            )
        }
        action(
            "discord.send",
            "Sends a message to a Discord channel.",
            "discord.send <channelId> <text...>",
        ) { invocation ->
            val channel = invocation.arguments.firstOrNull().orEmpty()
            val text = invocation.arguments.drop(1).joinToString(" ")
            when {
                channel.isBlank() || text.isBlank() -> ActionResult.error("usage: discord.send <channelId> <text...>")
                !runtime.send(channel, DiscordMessages.stripColors(text)) ->
                    ActionResult.error("discord bot is not connected")
                else -> ActionResult.ok("queued")
            }
        }
        action("discord.reload", "Reloads the configuration and reconnects the bot.", "discord.reload") {
            runtime.stop()
            config = DiscordConfig.load(storage)
            runtime.start()
            ActionResult.ok("reloaded — configured: ${config.configured()}")
        }
        action("discord.config.get", "Exports the bot configuration as JSON (token masked).", "discord.config.get") {
            ActionResult.ok(
                Json.encodeToString(
                    DiscordPublicConfig.of(config, runtime.connected(), links.all().size),
                ),
            )
        }
        action(
            "discord.config.set",
            "Updates the bot config and reconnects. Keys: token, guild, panelchannel, statuschannel, " +
                "auditchannel, notifychannel, categories, interval, codettl, confirmtimeout, " +
                "category.<name>, audit.<type>, normal, destructive, critical.",
            "discord.config.set <key=value>...",
        ) { invocation -> updateConfig(storage, invocation) }
        action(
            "discord.link.set",
            "Bootstrap: links a player to a Discord user id without a code.",
            "discord.link.set <player> <discordId>",
        ) { invocation ->
            val player = invocation.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: discord.link.set <player> <discordId>")
            val discordId = invocation.arguments.getOrNull(1)
                ?: return@action ActionResult.error("usage: discord.link.set <player> <discordId>")
            val uuid = context.resolvePlayerUuid(player)
                ?: return@action ActionResult.error("unknown player: $player (never joined)")
            val outcome = links.setLink(
                discordId = discordId,
                uuid = uuid,
                playerName = context.lastKnownName(uuid) ?: player,
                actor = invocation.actor ?: invocation.source.name.lowercase(),
            )
            when (outcome) {
                is LinkOutcome.Linked -> {
                    audit.link("created", outcome.link, "bootstrap")
                    ActionResult.ok("linked $player <-> $discordId")
                }
                is LinkOutcome.AlreadyLinked ->
                    ActionResult.error(
                        "already linked: ${outcome.existing.playerName} <-> ${outcome.existing.discordId}",
                    )
                LinkOutcome.InvalidCode -> ActionResult.error("unexpected outcome")
            }
        }
        action(
            "discord.link.remove",
            "Removes the link of a player or Discord user id.",
            "discord.link.remove <player|discordId>",
        ) { invocation ->
            val key = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: discord.link.remove <player|discordId>")
            val removed = links.unlinkDiscord(key)
                ?: context.resolvePlayerUuid(key)?.let(links::unlinkPlayer)
            if (removed == null) {
                ActionResult.error("no link for $key")
            } else {
                audit.link("removed", removed, invocation.actor ?: "admin")
                ActionResult.ok("unlinked ${removed.playerName} <-> ${removed.discordId}")
            }
        }
        action("discord.link.list", "Lists all account links.", "discord.link.list") {
            val all = links.all()
            if (all.isEmpty()) {
                ActionResult.ok("no links")
            } else {
                ActionResult.ok(
                    *all.map { "${it.playerName} (${it.uuid}) <-> ${it.discordName} (${it.discordId})" }
                        .toTypedArray(),
                )
            }
        }
    }

    private fun registerLinkCommand(audit: AuditLog) {
        action(
            "discord",
            "Links your Minecraft account with Discord.",
            "discord [code|unlink]",
            playerCommand = true,
            permission = PermissionGate.LINK_NODE,
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("missing executing player")
            val argument = invocation.arguments.getOrNull(1)?.trim()
            val uuid = context.resolvePlayerUuid(player)
                ?: return@action ActionResult.error(msg.formatFor(player, "game.error"))
            when {
                argument == null -> {
                    val code = links.createGameCode(uuid, player)
                    ActionResult.ok(
                        msg.formatFor(
                            player,
                            "game.code",
                            "code" to code,
                            "minutes" to "${config.linkCodeTtlSeconds / 60}",
                        ),
                    )
                }
                argument.equals("unlink", ignoreCase = true) -> {
                    val removed = links.unlinkPlayer(uuid)
                    if (removed == null) {
                        ActionResult.error(msg.formatFor(player, "game.nolink"))
                    } else {
                        audit.link("removed", removed, "in-game")
                        ActionResult.ok(msg.formatFor(player, "game.unlinked"))
                    }
                }
                else -> when (val outcome = links.redeemDiscordCode(argument, uuid, player)) {
                    is LinkOutcome.Linked -> {
                        audit.link("created", outcome.link, "discord-code")
                        ActionResult.ok(
                            msg.formatFor(player, "game.linked", "discord" to outcome.link.discordName),
                        )
                    }
                    is LinkOutcome.AlreadyLinked -> ActionResult.error(msg.formatFor(player, "game.already"))
                    LinkOutcome.InvalidCode -> ActionResult.error(msg.formatFor(player, "game.invalidcode"))
                }
            }
        }
    }

    private fun updateConfig(storage: AddonStorage, invocation: ActionInvocation): ActionResult {
        val overrides = invocation.arguments.mapNotNull { arg ->
            val parts = arg.split("=", limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1] else null
        }.toMap()

        /** Parses a comma-separated override into a clean list, or null. */
        fun list(key: String) = overrides[key]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val current = DiscordConfig.load(storage)
        val categoryOverrides = overrides.filterKeys { it.startsWith("category.") }
            .mapKeys { it.key.removePrefix("category.") }
        val auditOverrides = overrides.filterKeys { it.startsWith("audit.") }
            .mapKeys { it.key.removePrefix("audit.") }
        val updated = current.copy(
            botToken = overrides["token"]?.takeIf { it.isNotBlank() } ?: current.botToken,
            guildId = overrides["guild"] ?: current.guildId,
            panelChannelId = overrides["panelchannel"] ?: current.panelChannelId,
            statusChannelId = overrides["statuschannel"] ?: current.statusChannelId,
            auditChannelId = overrides["auditchannel"] ?: current.auditChannelId,
            notificationChannelId = overrides["notifychannel"] ?: current.notificationChannelId,
            notificationCategories = list("categories") ?: current.notificationCategories,
            categoryChannels = (current.categoryChannels + categoryOverrides).filterValues { it.isNotBlank() },
            auditChannels = (current.auditChannels + auditOverrides).filterValues { it.isNotBlank() },
            statusIntervalSeconds = overrides["interval"]?.toIntOrNull() ?: current.statusIntervalSeconds,
            linkCodeTtlSeconds = overrides["codettl"]?.toIntOrNull() ?: current.linkCodeTtlSeconds,
            confirmTimeoutSeconds = overrides["confirmtimeout"]?.toIntOrNull() ?: current.confirmTimeoutSeconds,
            normalActions = list("normal") ?: current.normalActions,
            destructiveActions = list("destructive") ?: current.destructiveActions,
            criticalActions = list("critical") ?: current.criticalActions,
        )
        DiscordConfig.save(storage, updated)
        runtime.stop()
        config = updated
        runtime.start()
        return ActionResult.ok("configuration saved — configured: ${updated.configured()}")
    }
}
