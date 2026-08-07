package org.helix.addons.discord

import dev.kord.common.Color
import dev.kord.common.entity.MessageFlag
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.TextInputStyle
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.updateEphemeralMessage
import dev.kord.core.entity.interaction.ComponentInteraction
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.GuildModalSubmitInteractionCreateEvent
import dev.kord.core.event.interaction.GuildSelectMenuInteractionCreateEvent
import dev.kord.core.event.interaction.GuildUserCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.container
import dev.kord.rest.builder.message.messageFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Persisted references to the bot's own messages (control panel and status
 * board), so restarts edit the existing messages instead of reposting.
 *
 * @property panelChannel channel of the control panel message.
 * @property panelMessage id of the control panel message.
 * @property statusChannel channel of the status board message.
 * @property statusMessage id of the status board message.
 */
@Serializable
data class BoardRefs(
    val panelChannel: String = "",
    val panelMessage: String = "",
    val statusChannel: String = "",
    val statusMessage: String = "",
)

/**
 * The Kord-facing runtime of the Discord bot: gateway lifecycle, guild
 * slash-command registration, interaction routing for every button, select,
 * modal and context-menu command, the two-click confirmation flows, the
 * setup wizard, the live status board and the batched audit/notification
 * senders.
 *
 * All authorization runs through [BotServices.gate]; there is no path from
 * a Discord interaction to an action execution that bypasses the per-action
 * permission nodes or the confirmation tiers.
 *
 * @property bot shared bot services.
 * @property storage addon-scoped storage for [BoardRefs].
 * @property publishState publishes bot state changes to the node's
 *   notification bus.
 */
class BotRuntime(
    private val bot: BotServices,
    private val storage: AddonStorage,
    private val publishState: (String) -> Unit,
) {
    private val ui = DiscordUi(bot)
    private val texts: DiscordMessages get() = bot.texts
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val kordRef = AtomicReference<Kord?>(null)
    private var scope: CoroutineScope? = null
    private val outbox = Channel<OutboxLine>(capacity = 512)
    private val statusPoke = Channel<Unit>(Channel.CONFLATED)
    private val setupSelections = ConcurrentHashMap<String, MutableMap<String, String>>()
    private var lastStatus: List<String> = emptyList()

    /**
     * Starts the bot when configured; otherwise stays idle.
     */
    fun start() {
        if (!bot.config().configured()) {
            return
        }
        val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = botScope
        botScope.launch {
            runCatching { runBot(botScope) }
                .onFailure { publishState("Discord bot stopped: ${it.message}") }
        }
    }

    /**
     * Logs out and stops all coroutines.
     */
    fun stop() {
        val kord = kordRef.getAndSet(null)
        if (kord != null) {
            runCatching { runBlocking { kord.logout() } }
        }
        scope?.cancel()
        scope = null
    }

    /**
     * Whether the gateway is connected.
     *
     * @return `true` while the bot is logged in.
     */
    fun connected(): Boolean = kordRef.get() != null

    /**
     * Queues a feed line for a channel; used by the audit trail, the
     * notification forwarding and the `discord.send` action. The flusher
     * batches queued lines per channel and posts them as Components-V2
     * containers — the bot never sends plain content messages.
     *
     * @param channelId target channel id.
     * @param text markdown text.
     * @param accent optional container accent color (RGB).
     * @return `false` when the bot is not running.
     */
    fun send(channelId: String, text: String, accent: Int? = null): Boolean {
        if (kordRef.get() == null || channelId.isBlank()) {
            return false
        }
        return outbox.trySend(OutboxLine(channelId, text, accent)).isSuccess
    }

    /**
     * Forwards a notification-bus event to its configured channel.
     *
     * @param category the notification category.
     * @param message the notification text.
     */
    fun notification(category: String, message: String) {
        val config = bot.config()
        if (category in config.notificationCategories) {
            send(config.channelForCategory(category), DiscordMessages.stripColors(message))
        }
    }

    /**
     * Requests an immediate status-board refresh, for example after an
     * action executed through the bot.
     */
    fun pokeStatus() {
        statusPoke.trySend(Unit)
    }

    private suspend fun runBot(botScope: CoroutineScope) {
        val kord = Kord(bot.config().botToken)
        kordRef.set(kord)
        registerCommands(kord)
        kord.on<GuildChatInputCommandInteractionCreateEvent> { runCatching { handleSlash(this) } }
        kord.on<GuildButtonInteractionCreateEvent> { runCatching { handleButton(this) } }
        kord.on<GuildSelectMenuInteractionCreateEvent> { runCatching { handleSelect(this) } }
        kord.on<GuildModalSubmitInteractionCreateEvent> { runCatching { handleModal(this) } }
        kord.on<GuildUserCommandInteractionCreateEvent> { runCatching { handleUserCommand(this) } }
        botScope.launch { flushOutbox(kord) }
        botScope.launch { statusLoop(kord) }
        publishState("Discord bot connecting to guild ${bot.config().guildId}")
        kord.login {
            intents = Intents(Intent.Guilds)
        }
        kordRef.compareAndSet(kord, null)
    }

    private suspend fun registerCommands(kord: Kord) {
        val guild = Snowflake(bot.config().guildId)
        kord.createGuildChatInputCommand(guild, "helix", texts.t("cmd.helix")) {
            subCommand("status", texts.t("cmd.status"))
            subCommand("panel", texts.t("cmd.panel"))
            subCommand("setup", texts.t("cmd.setup"))
            subCommand("link", texts.t("cmd.link")) {
                string("code", texts.t("cmd.link.code")) { required = false }
            }
            subCommand("unlink", texts.t("cmd.unlink"))
            subCommand("run", texts.t("cmd.run")) {
                string("action", texts.t("cmd.run.action")) { required = true }
                string("args", texts.t("cmd.run.args")) { required = false }
            }
        }
        kord.createGuildUserCommand(guild, texts.t("cmd.whois"))
    }

    // ------------------------------------------------------------------
    // Slash commands
    // ------------------------------------------------------------------

    private suspend fun handleSlash(event: GuildChatInputCommandInteractionCreateEvent) {
        val interaction = event.interaction
        val lang = interaction.locale?.language
        val userId = interaction.user.id.toString()
        val userName = interaction.user.username
        val command = interaction.command
        val respond: Responder = { build -> interaction.respondEphemeral { build() } }
        when ((command as? SubCommand)?.name) {
            "status" -> execute(userId, userName, lang, "platform.overview", emptyList(), respond)
            "panel" -> withNode(userId, userName, lang, PermissionGate.SETUP_NODE, respond) {
                repostPanels()
                respond { ui.notice(this, texts.tl(lang, "setup.panelposted")) }
            }
            "setup" -> withNode(userId, userName, lang, PermissionGate.SETUP_NODE, respond) {
                setupSelections.remove(userId)
                respond { ui.setupScreen(this, lang, emptyMap()) }
            }
            "link" -> handleLink(userId, userName, lang, command.strings["code"], respond)
            "unlink" -> handleUnlink(userId, userName, lang, respond)
            "run" -> {
                val action = command.strings["action"].orEmpty()
                val args = command.strings["args"].orEmpty().split(WHITESPACE).filter { it.isNotBlank() }
                execute(userId, userName, lang, action, args, respond)
            }
            else -> respond { ui.notice(this, texts.tl(lang, "ui.unknown"), success = false) }
        }
    }

    private suspend fun handleLink(
        userId: String,
        userName: String,
        lang: String?,
        code: String?,
        respond: Responder,
    ) {
        if (code.isNullOrBlank()) {
            val created = bot.links.createDiscordCode(userId, userName)
            respond {
                ui.notice(
                    this,
                    texts.tl(
                        lang,
                        "link.discordcode",
                        "code" to created,
                        "ttl" to "${bot.config().linkCodeTtlSeconds / 60}",
                    ),
                )
            }
            return
        }
        when (val outcome = bot.links.redeemGameCode(code, userId, userName)) {
            is LinkOutcome.Linked -> {
                bot.audit.link("created", outcome.link, "game-code")
                respond { ui.notice(this, texts.tl(lang, "link.done", "player" to outcome.link.playerName)) }
            }
            is LinkOutcome.AlreadyLinked -> respond {
                ui.notice(
                    this,
                    texts.tl(lang, "link.already", "player" to outcome.existing.playerName),
                    success = false,
                )
            }
            LinkOutcome.InvalidCode -> respond {
                ui.notice(this, texts.tl(lang, "link.invalidcode"), success = false)
            }
        }
    }

    private suspend fun handleUnlink(userId: String, userName: String, lang: String?, respond: Responder) {
        val removed = bot.links.unlinkDiscord(userId)
        if (removed == null) {
            respond { ui.notice(this, texts.tl(lang, "link.none"), success = false) }
        } else {
            bot.audit.link("removed", removed, "self-service")
            respond { ui.notice(this, texts.tl(lang, "link.unlinked", "player" to removed.playerName)) }
        }
    }

    private suspend fun handleUserCommand(event: GuildUserCommandInteractionCreateEvent) {
        val interaction = event.interaction
        val lang = interaction.locale?.language
        val userId = interaction.user.id.toString()
        val userName = interaction.user.username
        val respond: Responder = { build -> interaction.respondEphemeral { build() } }
        withNode(userId, userName, lang, PermissionGate.WHOIS_NODE, respond) {
            val targetId = interaction.targetId.toString()
            val targetName = interaction.users[interaction.targetId]?.username ?: targetId
            respond { ui.whoisScreen(this, lang, targetName, bot.links.byDiscord(targetId)) }
        }
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    private suspend fun handleButton(event: GuildButtonInteractionCreateEvent) {
        val interaction = event.interaction
        val route = DiscordUi.parse(interaction.componentId) ?: return
        val lang = interaction.locale?.language
        val userId = interaction.user.id.toString()
        val userName = interaction.user.username
        val respond = screenResponder(interaction)
        when (route.firstOrNull()) {
            "home", "open" -> openModule(userId, userName, lang, route.getOrNull(1), respond)
            "plrpg" -> gated(userId, userName, lang, "player.list", respond) {
                respond { ui.playersScreen(this, lang, route.getOrNull(1)?.toIntOrNull() ?: 0) }
            }
            "actpg" -> gated(userId, userName, lang, "actions.list", respond) {
                respond {
                    ui.actionListScreen(this, lang, route.getOrNull(1).orEmpty(), route.getOrNull(2)?.toIntOrNull() ?: 0)
                }
            }
            "actrun" -> startActionRun(interaction, userId, userName, lang, route.getOrNull(1).orEmpty(), respond)
            "x" -> execute(userId, userName, lang, route.getOrNull(1).orEmpty(), route.drop(2), respond)
            "modal" -> openInputModal(interaction, lang, route.drop(1))
            "cfok" -> resolveConfirmation(userId, userName, lang, route.getOrNull(1).orEmpty(), null, respond)
            "cfno" -> cancelConfirmation(userId, userName, lang, route.getOrNull(1).orEmpty(), respond)
            "cfcrit" -> openCriticalModal(interaction, lang, route.getOrNull(1).orEmpty(), respond)
            "stsave" -> saveSetup(userId, userName, lang, respond)
            "stcancel" -> {
                setupSelections.remove(userId)
                respond { ui.notice(this, texts.tl(lang, "setup.cancelled")) }
            }
            else -> respond { ui.notice(this, texts.tl(lang, "ui.unknown"), success = false) }
        }
    }

    private suspend fun handleSelect(event: GuildSelectMenuInteractionCreateEvent) {
        val interaction = event.interaction
        val route = DiscordUi.parse(interaction.componentId) ?: return
        val lang = interaction.locale?.language
        val userId = interaction.user.id.toString()
        val userName = interaction.user.username
        val value = interaction.values.firstOrNull().orEmpty()
        val respond = screenResponder(interaction)
        when (route.firstOrNull()) {
            "svc" -> gated(userId, userName, lang, "service.list", respond) {
                respond { ui.serviceDetail(this, lang, value) }
            }
            "task" -> gated(userId, userName, lang, "task.list", respond) {
                respond { ui.taskDetail(this, lang, value) }
            }
            "plr" -> gated(userId, userName, lang, "player.list", respond) {
                respond { ui.playerDetail(this, lang, value) }
            }
            "actgrp" -> gated(userId, userName, lang, "actions.list", respond) {
                respond { ui.actionListScreen(this, lang, value, 0) }
            }
            "actpick" -> gated(userId, userName, lang, "actions.list", respond) {
                respond { ui.actionDetail(this, lang, value) }
            }
            "st" -> {
                val target = route.getOrNull(1).orEmpty()
                val selections = setupSelections.getOrPut(userId) { ConcurrentHashMap() }
                selections[target] = value
                respond { ui.setupScreen(this, lang, selections) }
            }
            else -> respond { ui.notice(this, texts.tl(lang, "ui.unknown"), success = false) }
        }
    }

    private suspend fun openModule(
        userId: String,
        userName: String,
        lang: String?,
        module: String?,
        respond: Responder,
    ) {
        val gateAction = MODULE_GATES[module ?: "home"]
        if (gateAction != null) {
            gated(userId, userName, lang, gateAction, respond) {
                respond { renderModule(this, lang, module) }
            }
        } else {
            respond { renderModule(this, lang, module) }
        }
    }

    private fun renderModule(builder: MessageBuilder, lang: String?, module: String?) {
        when (module) {
            "services" -> ui.servicesScreen(builder, lang)
            "tasks" -> ui.tasksScreen(builder, lang)
            "players" -> ui.playersScreen(builder, lang, 0)
            "proxy" -> ui.proxyScreen(builder, lang)
            "perms" -> ui.permsScreen(builder, lang)
            "addons" -> ui.addonsScreen(builder, lang)
            "actions" -> ui.actionGroupsScreen(builder, lang)
            else -> ui.notice(builder, texts.tl(lang, "ui.overview.hint"))
        }
    }

    // ------------------------------------------------------------------
    // Modals
    // ------------------------------------------------------------------

    private suspend fun startActionRun(
        interaction: ComponentInteraction,
        userId: String,
        userName: String,
        lang: String?,
        action: String,
        respond: Responder,
    ) {
        val descriptor = bot.catalog.find(action)
        if (descriptor == null) {
            respond { ui.notice(this, texts.tl(lang, "ui.actions.unknown", "action" to action), success = false) }
            return
        }
        if (bot.catalog.argumentHint(descriptor).isBlank()) {
            execute(userId, userName, lang, action, emptyList(), respond)
            return
        }
        interaction.modal(
            texts.tl(lang, "modal.run.title", "action" to DiscordMessages.truncate(action, 30)),
            DiscordUi.id("m", "act", action),
        ) {
            label(texts.tl(lang, "modal.run.args")) {
                textInput(TextInputStyle.Short, "args") {
                    placeholder = DiscordMessages.truncate(bot.catalog.argumentHint(descriptor), 100)
                    required = false
                }
            }
        }
    }

    private suspend fun openInputModal(interaction: ComponentInteraction, lang: String?, route: List<String>) {
        val kind = route.firstOrNull().orEmpty()
        val target = route.getOrNull(1).orEmpty()
        when (kind) {
            "svccmd" -> interaction.modal(
                texts.tl(lang, "modal.svccmd.title", "service" to DiscordMessages.truncate(target, 25)),
                DiscordUi.id("m", "svccmd", target),
            ) {
                label(texts.tl(lang, "modal.svccmd.line")) {
                    textInput(TextInputStyle.Short, "line") { required = true }
                }
            }
            "kick" -> reasonModal(interaction, lang, "kick", target, required = false)
            "warn" -> reasonModal(interaction, lang, "warn", target, required = true)
            "msg" -> interaction.modal(
                texts.tl(lang, "modal.msg.title", "player" to DiscordMessages.truncate(target, 25)),
                DiscordUi.id("m", "msg", target),
            ) {
                label(texts.tl(lang, "modal.msg.text")) {
                    textInput(TextInputStyle.Paragraph, "text") { required = true }
                }
            }
            "ban" -> durationReasonModal(interaction, lang, "ban", target)
            "mute" -> durationReasonModal(interaction, lang, "mute", target)
            "broadcast" -> interaction.modal(
                texts.tl(lang, "modal.broadcast.title"),
                DiscordUi.id("m", "broadcast"),
            ) {
                label(texts.tl(lang, "modal.msg.text")) {
                    textInput(TextInputStyle.Paragraph, "text") { required = true }
                }
            }
            "permuser" -> singleInputModal(interaction, lang, "permuser", "modal.perm.player")
            "permgroup" -> singleInputModal(interaction, lang, "permgroup", "modal.perm.group")
            "permaddgroup" -> playerPlusModal(interaction, lang, "permaddgroup", "modal.perm.group")
            "permremovegroup" -> playerPlusModal(interaction, lang, "permremovegroup", "modal.perm.group")
            "permgrant" -> playerPlusModal(interaction, lang, "permgrant", "modal.perm.node")
            "permrevoke" -> playerPlusModal(interaction, lang, "permrevoke", "modal.perm.node")
        }
    }

    private suspend fun reasonModal(
        interaction: ComponentInteraction,
        lang: String?,
        kind: String,
        player: String,
        required: Boolean,
    ) {
        interaction.modal(
            texts.tl(lang, "modal.$kind.title", "player" to DiscordMessages.truncate(player, 25)),
            DiscordUi.id("m", kind, player),
        ) {
            label(texts.tl(lang, "modal.reason")) {
                textInput(TextInputStyle.Short, "reason") { this.required = required }
            }
        }
    }

    private suspend fun durationReasonModal(
        interaction: ComponentInteraction,
        lang: String?,
        kind: String,
        player: String,
    ) {
        interaction.modal(
            texts.tl(lang, "modal.$kind.title", "player" to DiscordMessages.truncate(player, 25)),
            DiscordUi.id("m", kind, player),
        ) {
            label(texts.tl(lang, "modal.duration")) {
                textInput(TextInputStyle.Short, "duration") {
                    placeholder = "30m / 12h / 7d"
                    required = false
                }
            }
            label(texts.tl(lang, "modal.reason")) {
                textInput(TextInputStyle.Short, "reason") { required = false }
            }
        }
    }

    private suspend fun singleInputModal(
        interaction: ComponentInteraction,
        lang: String?,
        kind: String,
        labelKey: String,
    ) {
        interaction.modal(texts.tl(lang, "modal.$kind.title"), DiscordUi.id("m", kind)) {
            label(texts.tl(lang, labelKey)) {
                textInput(TextInputStyle.Short, "value") { required = true }
            }
        }
    }

    private suspend fun playerPlusModal(
        interaction: ComponentInteraction,
        lang: String?,
        kind: String,
        secondLabelKey: String,
    ) {
        interaction.modal(texts.tl(lang, "modal.$kind.title"), DiscordUi.id("m", kind)) {
            label(texts.tl(lang, "modal.perm.player")) {
                textInput(TextInputStyle.Short, "player") { required = true }
            }
            label(texts.tl(lang, secondLabelKey)) {
                textInput(TextInputStyle.Short, "value") { required = true }
            }
        }
    }

    private suspend fun handleModal(event: GuildModalSubmitInteractionCreateEvent) {
        val interaction = event.interaction
        val route = DiscordUi.parse(interaction.modalId) ?: return
        if (route.firstOrNull() != "m") {
            return
        }
        val lang = interaction.locale?.language
        val userId = interaction.user.id.toString()
        val userName = interaction.user.username
        val respond: Responder = { build -> interaction.respondEphemeral { build() } }
        val kind = route.getOrNull(1).orEmpty()
        val target = route.getOrNull(2).orEmpty()
        val input = { id: String -> interaction.textInputs[id]?.value?.trim().orEmpty() }
        when (kind) {
            "act" -> execute(
                userId,
                userName,
                lang,
                target,
                input("args").split(WHITESPACE).filter { it.isNotBlank() },
                respond,
            )
            "svccmd" -> execute(
                userId,
                userName,
                lang,
                "service.command",
                listOf(target) + input("line").split(WHITESPACE).filter { it.isNotBlank() },
                respond,
            )
            "kick" -> execute(
                userId,
                userName,
                lang,
                "player.kick",
                listOf(target) + words(input("reason")),
                respond,
            )
            "msg" -> execute(userId, userName, lang, "player.message", listOf(target) + words(input("text")), respond)
            "broadcast" -> execute(userId, userName, lang, "player.broadcast", words(input("text")), respond)
            "warn" -> execute(userId, userName, lang, "warn", listOf(target) + words(input("reason")), respond)
            "mute" -> execute(
                userId,
                userName,
                lang,
                "mute",
                listOf(target) + words(input("duration")) + words(input("reason")),
                respond,
            )
            "ban" -> executeBan(userId, userName, lang, target, input("duration"), input("reason"), respond)
            "permuser" -> execute(userId, userName, lang, "perm.user.info", listOf(input("value")), respond)
            "permgroup" -> execute(userId, userName, lang, "perm.group.info", listOf(input("value")), respond)
            "permaddgroup" -> execute(
                userId, userName, lang, "perm.user.addgroup", listOf(input("player"), input("value")), respond,
            )
            "permremovegroup" -> execute(
                userId, userName, lang, "perm.user.removegroup", listOf(input("player"), input("value")), respond,
            )
            "permgrant" -> execute(
                userId, userName, lang, "perm.user.grant", listOf(input("player"), input("value")), respond,
            )
            "permrevoke" -> execute(
                userId, userName, lang, "perm.user.revoke", listOf(input("player"), input("value")), respond,
            )
            "crit" -> resolveConfirmation(userId, userName, lang, target, input("text"), respond)
            else -> respond { ui.notice(this, texts.tl(lang, "ui.unknown"), success = false) }
        }
    }

    private suspend fun executeBan(
        userId: String,
        userName: String,
        lang: String?,
        player: String,
        duration: String,
        reason: String,
        respond: Responder,
    ) {
        val access = bot.gate.forAction(userId, "ban.set", bot.catalog.find("ban.set")?.permission)
        val actor = (access as? Access.Granted)?.actorName ?: userName
        val args = listOf(player, actor) + words(duration) + words(reason)
        execute(userId, userName, lang, "ban.set", args, respond)
    }

    // ------------------------------------------------------------------
    // Execution and confirmation
    // ------------------------------------------------------------------

    private suspend fun execute(
        userId: String,
        userName: String,
        lang: String?,
        action: String,
        args: List<String>,
        respond: Responder,
    ) {
        val descriptor = bot.catalog.find(action)
        if (descriptor == null) {
            respond { ui.notice(this, texts.tl(lang, "ui.actions.unknown", "action" to action), success = false) }
            return
        }
        when (val access = bot.gate.forAction(userId, action, descriptor.permission)) {
            Access.NotLinked -> {
                bot.audit.denied(userName, action, "not linked")
                respond { ui.notice(this, texts.tl(lang, "gate.notlinked"), success = false) }
            }
            is Access.Denied -> {
                bot.audit.denied(userName, action, access.node)
                respond {
                    ui.notice(this, texts.tl(lang, "gate.denied", "node" to "`${access.node}`"), success = false)
                }
            }
            is Access.Granted -> when (ActionTiers.classify(action, bot.config())) {
                ActionTier.NORMAL -> runAction(userName, access.actorName, lang, action, args, respond)
                else -> {
                    val tier = ActionTiers.classify(action, bot.config())
                    val pending = bot.confirmations.create(
                        discordId = userId,
                        actorName = access.actorName,
                        action = action,
                        arguments = args,
                        tier = tier,
                        expectedText = args.firstOrNull() ?: action,
                    )
                    respond { ui.confirmScreen(this, lang, pending) }
                }
            }
        }
    }

    private suspend fun runAction(
        userName: String,
        actorName: String,
        lang: String?,
        action: String,
        args: List<String>,
        respond: Responder,
    ) {
        val descriptor = bot.catalog.find(action)
        if (descriptor == null) {
            respond { ui.notice(this, texts.tl(lang, "ui.actions.unknown", "action" to action), success = false) }
            return
        }
        val result = bot.invokeAs(actorName, descriptor, args)
        bot.audit.discordAction(userName, actorName, action, args, result)
        pokeStatus()
        respond { ui.resultScreen(this, lang, action, result.success, result.lines) }
    }

    private suspend fun resolveConfirmation(
        userId: String,
        userName: String,
        lang: String?,
        confirmationId: String,
        typedText: String?,
        respond: Responder,
    ) {
        when (val outcome = bot.confirmations.confirm(confirmationId, userId, typedText)) {
            is ConfirmOutcome.Ready -> {
                val pending = outcome.pending
                val descriptor = bot.catalog.find(pending.action)
                when (val access = bot.gate.forAction(userId, pending.action, descriptor?.permission)) {
                    is Access.Granted ->
                        runAction(userName, access.actorName, lang, pending.action, pending.arguments, respond)
                    else -> {
                        bot.audit.denied(userName, pending.action, PermissionGate.actionNode(pending.action))
                        respond { ui.notice(this, texts.tl(lang, "gate.denied.generic"), success = false) }
                    }
                }
            }
            is ConfirmOutcome.Expired -> {
                bot.audit.confirmation("expired", outcome.pending, userName)
                respond { ui.notice(this, texts.tl(lang, "confirm.expired"), success = false) }
            }
            is ConfirmOutcome.TextMismatch -> {
                bot.audit.confirmation("mismatch", outcome.pending, userName)
                respond {
                    ui.notice(
                        this,
                        texts.tl(lang, "confirm.mismatch", "expected" to "`${outcome.pending.expectedText}`"),
                        success = false,
                    )
                }
            }
            ConfirmOutcome.WrongUser -> respond {
                ui.notice(this, texts.tl(lang, "confirm.wronguser"), success = false)
            }
            else -> respond { ui.notice(this, texts.tl(lang, "confirm.stale"), success = false) }
        }
    }

    private suspend fun cancelConfirmation(
        userId: String,
        userName: String,
        lang: String?,
        confirmationId: String,
        respond: Responder,
    ) {
        when (val outcome = bot.confirmations.cancel(confirmationId, userId)) {
            is ConfirmOutcome.Cancelled -> {
                bot.audit.confirmation("cancelled", outcome.pending, userName)
                respond { ui.notice(this, texts.tl(lang, "confirm.cancelled")) }
            }
            is ConfirmOutcome.Expired -> {
                bot.audit.confirmation("expired", outcome.pending, userName)
                respond { ui.notice(this, texts.tl(lang, "confirm.expired"), success = false) }
            }
            ConfirmOutcome.WrongUser -> respond {
                ui.notice(this, texts.tl(lang, "confirm.wronguser"), success = false)
            }
            else -> respond { ui.notice(this, texts.tl(lang, "confirm.stale"), success = false) }
        }
    }

    private suspend fun openCriticalModal(
        interaction: ComponentInteraction,
        lang: String?,
        confirmationId: String,
        respond: Responder,
    ) {
        val pending = bot.confirmations.peek(confirmationId)
        if (pending == null) {
            respond { ui.notice(this, texts.tl(lang, "confirm.stale"), success = false) }
            return
        }
        interaction.modal(
            texts.tl(lang, "modal.crit.title"),
            DiscordUi.id("m", "crit", confirmationId),
        ) {
            label(texts.tl(lang, "modal.crit.text", "expected" to DiscordMessages.truncate(pending.expectedText, 40))) {
                textInput(TextInputStyle.Short, "text") {
                    placeholder = DiscordMessages.truncate(pending.expectedText, 100)
                    required = true
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Gates and responders
    // ------------------------------------------------------------------

    private suspend fun gated(
        userId: String,
        userName: String,
        lang: String?,
        action: String,
        respond: Responder,
        block: suspend () -> Unit,
    ) {
        when (val access = bot.gate.forAction(userId, action, bot.catalog.find(action)?.permission)) {
            is Access.Granted -> block()
            Access.NotLinked -> {
                bot.audit.denied(userName, action, "not linked")
                respond { ui.notice(this, texts.tl(lang, "gate.notlinked"), success = false) }
            }
            is Access.Denied -> {
                bot.audit.denied(userName, action, access.node)
                respond {
                    ui.notice(this, texts.tl(lang, "gate.denied", "node" to "`${access.node}`"), success = false)
                }
            }
        }
    }

    private suspend fun withNode(
        userId: String,
        userName: String,
        lang: String?,
        node: String,
        respond: Responder,
        block: suspend () -> Unit,
    ) {
        when (val access = bot.gate.forNode(userId, node)) {
            is Access.Granted -> block()
            Access.NotLinked -> {
                bot.audit.denied(userName, node, "not linked")
                respond { ui.notice(this, texts.tl(lang, "gate.notlinked"), success = false) }
            }
            is Access.Denied -> {
                bot.audit.denied(userName, node, access.node)
                respond {
                    ui.notice(this, texts.tl(lang, "gate.denied", "node" to "`${access.node}`"), success = false)
                }
            }
        }
    }

    private fun screenResponder(interaction: ComponentInteraction): Responder = { build ->
        if (interaction.message.flags?.contains(MessageFlag.Ephemeral) == true) {
            interaction.updateEphemeralMessage { build() }
        } else {
            interaction.respondEphemeral { build() }
        }
    }

    // ------------------------------------------------------------------
    // Setup, panels and status board
    // ------------------------------------------------------------------

    private suspend fun saveSetup(userId: String, userName: String, lang: String?, respond: Responder) {
        val access = bot.gate.forNode(userId, PermissionGate.SETUP_NODE)
        if (access !is Access.Granted) {
            bot.audit.denied(userName, PermissionGate.SETUP_NODE, "setup")
            respond { ui.notice(this, texts.tl(lang, "gate.denied.generic"), success = false) }
            return
        }
        val selections = setupSelections.remove(userId).orEmpty()
        if (selections.isEmpty()) {
            respond { ui.notice(this, texts.tl(lang, "setup.nothing"), success = false) }
            return
        }
        val current = bot.config()
        val updated = current.copy(
            panelChannelId = selections["panel"] ?: current.panelChannelId,
            statusChannelId = selections["status"] ?: current.statusChannelId,
            auditChannelId = selections["audit"] ?: current.auditChannelId,
            notificationChannelId = selections["notify"] ?: current.notificationChannelId,
        )
        bot.saveConfig(updated)
        repostPanels()
        bot.audit.discordAction(
            userName,
            access.actorName,
            "discord.setup",
            selections.map { "${it.key}=${it.value}" },
            org.helix.api.action.ActionResult.ok("configured"),
        )
        respond { ui.notice(this, texts.tl(lang, "setup.saved", "count" to "${selections.size}")) }
    }

    /**
     * Posts or refreshes the persistent control panel and the status
     * board in their configured channels.
     */
    suspend fun repostPanels() {
        val kord = kordRef.get() ?: return
        val config = bot.config()
        var refs = loadRefs()
        if (config.panelChannelId.isNotBlank()) {
            val id = upsertMessage(kord, config.panelChannelId, refs.panelChannel, refs.panelMessage) { ui.home(this) }
            refs = refs.copy(panelChannel = config.panelChannelId, panelMessage = id.orEmpty())
        }
        if (config.statusChannelId.isNotBlank()) {
            val id = upsertMessage(kord, config.statusChannelId, refs.statusChannel, refs.statusMessage) {
                ui.statusBoard(this)
            }
            refs = refs.copy(statusChannel = config.statusChannelId, statusMessage = id.orEmpty())
            lastStatus = ui.statusLines()
        }
        storage.write(BOARDS_DOCUMENT, json.encodeToString(refs))
    }

    private suspend fun upsertMessage(
        kord: Kord,
        channelId: String,
        previousChannel: String,
        previousMessage: String,
        build: MessageBuilder.() -> Unit,
    ): String? {
        if (previousMessage.isNotBlank() && previousChannel == channelId) {
            val edited = runCatching {
                kord.rest.channel.editMessage(Snowflake(channelId), Snowflake(previousMessage)) { build() }
            }
            if (edited.isSuccess) {
                return previousMessage
            }
        }
        return runCatching {
            kord.rest.channel.createMessage(Snowflake(channelId)) { build() }.id.toString()
        }.getOrNull()
    }

    private suspend fun statusLoop(kord: Kord) {
        while (scope?.isActive == true && kordRef.get() != null) {
            withTimeoutOrNull(bot.config().statusIntervalSeconds.coerceAtLeast(10) * 1000L) {
                statusPoke.receive()
            }
            val config = bot.config()
            if (config.statusChannelId.isBlank()) {
                continue
            }
            val refs = loadRefs()
            if (refs.statusMessage.isBlank() || refs.statusChannel != config.statusChannelId) {
                continue
            }
            val lines = runCatching { ui.statusLines() }.getOrNull() ?: continue
            if (lines == lastStatus) {
                continue
            }
            lastStatus = lines
            runCatching {
                kord.rest.channel.editMessage(Snowflake(refs.statusChannel), Snowflake(refs.statusMessage)) {
                    ui.statusBoard(this)
                }
            }
            bot.confirmations.prune()
        }
    }

    private suspend fun flushOutbox(kord: Kord) {
        while (scope?.isActive == true && kordRef.get() != null) {
            val first = outbox.receive()
            val batch = linkedMapOf<Pair<String, Int?>, MutableList<String>>()
            batch.getOrPut(first.channelId to first.accent) { mutableListOf() } += first.text
            withTimeoutOrNull(BATCH_WINDOW_MS) {
                while (true) {
                    val next = outbox.receive()
                    batch.getOrPut(next.channelId to next.accent) { mutableListOf() } += next.text
                }
            }
            batch.forEach { (key, lines) ->
                val (channelId, accent) = key
                chunk(lines).forEach { text ->
                    runCatching {
                        kord.rest.channel.createMessage(Snowflake(channelId)) {
                            messageFlags { +MessageFlag.IsComponentsV2 }
                            container {
                                accent?.let { accentColor = Color(it) }
                                textDisplay(text)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * One queued feed line awaiting its Components-V2 batch.
     *
     * @property channelId target channel id.
     * @property text markdown text.
     * @property accent optional container accent color (RGB).
     */
    private data class OutboxLine(val channelId: String, val text: String, val accent: Int?)

    private fun chunk(lines: List<String>): List<String> {
        val chunks = mutableListOf<StringBuilder>()
        lines.forEach { line ->
            val trimmed = DiscordMessages.truncate(line, MESSAGE_LIMIT)
            val current = chunks.lastOrNull()
            if (current == null || current.length + trimmed.length + 1 > MESSAGE_LIMIT) {
                chunks += StringBuilder(trimmed)
            } else {
                current.append('\n').append(trimmed)
            }
        }
        return chunks.map { it.toString() }
    }

    private fun loadRefs(): BoardRefs =
        storage.read(BOARDS_DOCUMENT)?.let { raw ->
            runCatching { json.decodeFromString<BoardRefs>(raw) }.getOrNull()
        } ?: BoardRefs()

    private fun words(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotBlank() }

    private companion object {
        /** Storage document holding [BoardRefs]. */
        const val BOARDS_DOCUMENT = "boards"

        /** How long queued outbox lines are collected before sending. */
        const val BATCH_WINDOW_MS = 1500L

        /** Discord's per-message text-display budget, minus headroom. */
        const val MESSAGE_LIMIT = 3800

        val WHITESPACE = Regex("\\s+")

        /** Read action gating each module screen. */
        val MODULE_GATES = mapOf(
            "services" to "service.list",
            "tasks" to "task.list",
            "players" to "player.list",
            "proxy" to "proxy.maintenance",
            "perms" to "perm.group.list",
            "addons" to "actions.list",
            "actions" to "actions.list",
        )
    }
}

/**
 * Responds to an interaction with one rendered screen.
 */
private typealias Responder = suspend (MessageBuilder.() -> Unit) -> Unit
