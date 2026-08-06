package org.helix.addons.discord

import dev.kord.common.Color
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.MessageFlag
import dev.kord.rest.builder.component.ActionRowBuilder
import dev.kord.rest.builder.component.ContainerBuilder
import dev.kord.rest.builder.component.actionRow
import dev.kord.rest.builder.component.option
import dev.kord.rest.builder.component.separator
import dev.kord.rest.builder.component.textDisplay
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.container
import dev.kord.rest.builder.message.messageFlags

/**
 * Renders every Discord screen of the bot as Components-V2 messages:
 * the persistent control panel, the curated module screens (services,
 * tasks, players, proxy/platform, permissions, addons), the dynamic
 * action browser, the confirmation and result screens, the setup wizard
 * and the live status board.
 *
 * Rendering is stateless — every screen is rebuilt from live node data on
 * each interaction, so pagination and navigation survive restarts.
 *
 * @property bot shared bot services.
 */
class DiscordUi(private val bot: BotServices) {
    private val texts: DiscordMessages get() = bot.texts

    /**
     * The persistent control panel posted into the panel channel.
     *
     * @param b target message builder.
     */
    fun home(b: MessageBuilder) {
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.t("ui.panel.title")}" }
            textDisplay { content = texts.t("ui.panel.intro") }
            separator { }
            actionRow {
                navButton("services", texts.t("ui.module.services"))
                navButton("tasks", texts.t("ui.module.tasks"))
                navButton("players", texts.t("ui.module.players"))
                navButton("proxy", texts.t("ui.module.proxy"))
            }
            actionRow {
                navButton("perms", texts.t("ui.module.perms"))
                navButton("addons", texts.t("ui.module.addons"))
                navButton("actions", texts.t("ui.module.actions"))
            }
        }
    }

    /**
     * The services module: live list plus a select to drill into one
     * service.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     */
    fun servicesScreen(b: MessageBuilder, lang: String?) {
        val lines = bot.run("service.list").lines
        val services = CloudLines.services(lines)
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.services.title")}" }
            textDisplay { content = codeBlock(lines) }
            if (services.isNotEmpty()) {
                actionRow {
                    stringSelect(id("svc")) {
                        placeholder = texts.tl(lang, "ui.services.select")
                        services.take(SELECT_LIMIT).forEach { service ->
                            option("${service.id} · ${service.state} · ${service.players}", service.id)
                        }
                    }
                }
            }
            navRow(this, lang, "services")
        }
    }

    /**
     * One service with its control buttons.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param serviceId the service id.
     */
    fun serviceDetail(b: MessageBuilder, lang: String?, serviceId: String) {
        val line = bot.run("service.list").lines.firstOrNull { it.startsWith("$serviceId ") }
            ?: texts.tl(lang, "ui.services.gone", "service" to serviceId)
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.service.title", "service" to serviceId)}" }
            textDisplay { content = codeBlock(listOf(line)) }
            actionRow {
                interactionButton(ButtonStyle.Secondary, id("x", "service.logs", serviceId)) {
                    label = texts.tl(lang, "ui.service.logs")
                }
                interactionButton(ButtonStyle.Primary, id("x", "service.restart", serviceId)) {
                    label = texts.tl(lang, "ui.service.restart")
                }
                interactionButton(ButtonStyle.Danger, id("x", "service.stop", serviceId)) {
                    label = texts.tl(lang, "ui.service.stop")
                }
                interactionButton(ButtonStyle.Danger, id("x", "service.kill", serviceId)) {
                    label = texts.tl(lang, "ui.service.kill")
                }
            }
            actionRow {
                interactionButton(ButtonStyle.Secondary, id("modal", "svccmd", serviceId)) {
                    label = texts.tl(lang, "ui.service.command")
                }
                backButton(this, lang, "services")
            }
        }
    }

    /**
     * The tasks module: live list plus a select to drill into one task.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     */
    fun tasksScreen(b: MessageBuilder, lang: String?) {
        val lines = bot.run("task.list").lines
        val tasks = CloudLines.taskNames(lines)
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.tasks.title")}" }
            textDisplay { content = codeBlock(lines) }
            if (tasks.isNotEmpty()) {
                actionRow {
                    stringSelect(id("task")) {
                        placeholder = texts.tl(lang, "ui.tasks.select")
                        tasks.take(SELECT_LIMIT).forEach { option(it, it) }
                    }
                }
            }
            navRow(this, lang, "tasks")
        }
    }

    /**
     * One task with its control buttons.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param task the task name.
     */
    fun taskDetail(b: MessageBuilder, lang: String?, task: String) {
        val info = bot.run("task.info", task).lines
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.task.title", "task" to task)}" }
            textDisplay { content = codeBlock(info) }
            actionRow {
                interactionButton(ButtonStyle.Success, id("x", "service.start", task)) {
                    label = texts.tl(lang, "ui.task.start")
                }
                interactionButton(ButtonStyle.Primary, id("x", "task.restart", task)) {
                    label = texts.tl(lang, "ui.task.rollingrestart")
                }
                interactionButton(ButtonStyle.Danger, id("x", "task.delete", task)) {
                    label = texts.tl(lang, "ui.task.delete")
                }
                backButton(this, lang, "tasks")
            }
        }
    }

    /**
     * The players module: online list with pagination, player select and
     * broadcast.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param page zero-based page over the online players.
     */
    fun playersScreen(b: MessageBuilder, lang: String?, page: Int) {
        val players = bot.onlinePlayers()
        val pages = (players.size + SELECT_LIMIT - 1) / SELECT_LIMIT
        val current = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val slice = players.drop(current * SELECT_LIMIT).take(SELECT_LIMIT)
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.players.title", "count" to "${players.size}")}" }
            if (players.isEmpty()) {
                textDisplay { content = texts.tl(lang, "ui.players.none") }
            } else {
                textDisplay { content = DiscordMessages.truncate(players.joinToString(", ") { it.name }, TEXT_LIMIT) }
                actionRow {
                    stringSelect(id("plr", "$current")) {
                        placeholder = texts.tl(lang, "ui.players.select")
                        slice.forEach { option(it.name, it.name) }
                    }
                }
            }
            if (pages > 1) {
                actionRow {
                    interactionButton(ButtonStyle.Secondary, id("plrpg", "${current - 1}")) {
                        label = "◀"
                        disabled = current == 0
                    }
                    interactionButton(ButtonStyle.Secondary, id("plrpg", "${current + 1}")) {
                        label = "▶ ${current + 1}/$pages"
                        disabled = current >= pages - 1
                    }
                }
            }
            actionRow {
                interactionButton(ButtonStyle.Primary, id("modal", "broadcast")) {
                    label = texts.tl(lang, "ui.players.broadcast")
                }
                backButton(this, lang, null)
                refreshButton(this, lang, "players")
            }
        }
    }

    /**
     * One player with moderation buttons; buttons of absent addons are
     * hidden.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param player the player name.
     */
    fun playerDetail(b: MessageBuilder, lang: String?, player: String) {
        val online = bot.onlinePlayers().firstOrNull { it.name.equals(player, ignoreCase = true) }
        val uuid = online?.uuid ?: bot.resolveUuid(player)
        val link = uuid?.let { bot.links.byPlayer(it) }
        val facts = buildList {
            add(texts.tl(lang, "ui.player.online", "state" to onOff(lang, online != null)))
            uuid?.let { add("UUID: `$it`") }
            online?.proxyServiceId?.takeIf { it.isNotBlank() }?.let { add("Proxy: `$it`") }
            link?.let { add(texts.tl(lang, "ui.player.linked", "discord" to it.discordName)) }
        }
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.player.title", "player" to player)}" }
            textDisplay { content = facts.joinToString("\n") }
            actionRow {
                interactionButton(ButtonStyle.Danger, id("modal", "kick", player)) {
                    label = texts.tl(lang, "ui.player.kick")
                }
                interactionButton(ButtonStyle.Secondary, id("modal", "msg", player)) {
                    label = texts.tl(lang, "ui.player.message")
                }
                if (bot.catalog.find("warn") != null) {
                    interactionButton(ButtonStyle.Danger, id("modal", "warn", player)) {
                        label = texts.tl(lang, "ui.player.warn")
                    }
                }
                if (bot.catalog.find("mute") != null) {
                    interactionButton(ButtonStyle.Danger, id("modal", "mute", player)) {
                        label = texts.tl(lang, "ui.player.mute")
                    }
                }
            }
            if (bot.catalog.find("ban.set") != null) {
                actionRow {
                    interactionButton(ButtonStyle.Danger, id("modal", "ban", player)) {
                        label = texts.tl(lang, "ui.player.ban")
                    }
                    interactionButton(ButtonStyle.Success, id("x", "ban.pardon", player)) {
                        label = texts.tl(lang, "ui.player.pardon")
                    }
                    interactionButton(ButtonStyle.Secondary, id("x", "ban.history", player)) {
                        label = texts.tl(lang, "ui.player.history")
                    }
                }
            }
            actionRow {
                backButton(this, lang, "players")
            }
        }
    }

    /**
     * The proxy/platform module: maintenance and the platform-wide
     * restart/stop controls.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     */
    fun proxyScreen(b: MessageBuilder, lang: String?) {
        val maintenance = bot.run("proxy.maintenance").lines
        b.v2()
        b.container {
            accentColor = WARN
            textDisplay { content = "## ${texts.tl(lang, "ui.proxy.title")}" }
            textDisplay { content = codeBlock(maintenance) }
            actionRow {
                interactionButton(ButtonStyle.Danger, id("x", "proxy.maintenance", "on")) {
                    label = texts.tl(lang, "ui.proxy.maintenance.on")
                }
                interactionButton(ButtonStyle.Success, id("x", "proxy.maintenance", "off")) {
                    label = texts.tl(lang, "ui.proxy.maintenance.off")
                }
            }
            separator { }
            textDisplay { content = texts.tl(lang, "ui.proxy.platform") }
            actionRow {
                interactionButton(ButtonStyle.Danger, id("x", "platform.restart")) {
                    label = texts.tl(lang, "ui.proxy.backendrestart")
                }
                interactionButton(ButtonStyle.Danger, id("x", "launcher.restart")) {
                    label = texts.tl(lang, "ui.proxy.launcherrestart")
                }
                interactionButton(ButtonStyle.Danger, id("x", "platform.stop")) {
                    label = texts.tl(lang, "ui.proxy.stop")
                }
            }
            navRow(this, lang, "proxy")
        }
    }

    /**
     * The permissions module: group overview plus modals for lookups and
     * grants.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     */
    fun permsScreen(b: MessageBuilder, lang: String?) {
        val groups = bot.run("perm.group.list").lines
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.perms.title")}" }
            textDisplay { content = codeBlock(groups) }
            actionRow {
                interactionButton(ButtonStyle.Secondary, id("modal", "permuser")) {
                    label = texts.tl(lang, "ui.perms.userinfo")
                }
                interactionButton(ButtonStyle.Secondary, id("modal", "permgroup")) {
                    label = texts.tl(lang, "ui.perms.groupinfo")
                }
            }
            actionRow {
                interactionButton(ButtonStyle.Primary, id("modal", "permaddgroup")) {
                    label = texts.tl(lang, "ui.perms.addgroup")
                }
                interactionButton(ButtonStyle.Danger, id("modal", "permremovegroup")) {
                    label = texts.tl(lang, "ui.perms.removegroup")
                }
                interactionButton(ButtonStyle.Primary, id("modal", "permgrant")) {
                    label = texts.tl(lang, "ui.perms.grant")
                }
                interactionButton(ButtonStyle.Danger, id("modal", "permrevoke")) {
                    label = texts.tl(lang, "ui.perms.revoke")
                }
            }
            navRow(this, lang, "perms")
        }
    }

    /**
     * The addons module: every installed addon with state and action
     * count.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     */
    fun addonsScreen(b: MessageBuilder, lang: String?) {
        val lines = bot.installedAddons().map { addon ->
            "${addon.manifest.id} ${addon.manifest.version} [${addon.state}] " +
                texts.tl(lang, "ui.addons.actions", "count" to "${addon.actions.size}") +
                (addon.failureReason?.let { " — $it" } ?: "")
        }
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.addons.title", "count" to "${lines.size}")}" }
            textDisplay { content = codeBlock(lines) }
            navRow(this, lang, "addons")
        }
    }

    /**
     * The action browser entry: one select over all action groups.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     */
    fun actionGroupsScreen(b: MessageBuilder, lang: String?) {
        val groups = bot.catalog.groups()
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.actions.title")}" }
            textDisplay { content = texts.tl(lang, "ui.actions.intro") }
            actionRow {
                stringSelect(id("actgrp")) {
                    placeholder = texts.tl(lang, "ui.actions.selectgroup")
                    groups.take(SELECT_LIMIT).forEach { group ->
                        option("$group (${bot.catalog.actionsIn(group).size})", group)
                    }
                }
            }
            navRow(this, lang, "actions")
        }
    }

    /**
     * The actions of one group, paged.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param group the group name.
     * @param page zero-based page.
     */
    fun actionListScreen(b: MessageBuilder, lang: String?, group: String, page: Int) {
        val all = bot.catalog.actionsIn(group)
        val pages = (all.size + SELECT_LIMIT - 1) / SELECT_LIMIT
        val current = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val slice = all.drop(current * SELECT_LIMIT).take(SELECT_LIMIT)
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "ui.actions.group", "group" to group)}" }
            actionRow {
                stringSelect(id("actpick")) {
                    placeholder = texts.tl(lang, "ui.actions.selectaction")
                    slice.forEach { option(DiscordMessages.truncate(it.usage, 100), it.name) }
                }
            }
            if (pages > 1) {
                actionRow {
                    interactionButton(ButtonStyle.Secondary, id("actpg", group, "${current - 1}")) {
                        label = "◀"
                        disabled = current == 0
                    }
                    interactionButton(ButtonStyle.Secondary, id("actpg", group, "${current + 1}")) {
                        label = "▶ ${current + 1}/$pages"
                        disabled = current >= pages - 1
                    }
                }
            }
            actionRow {
                backButton(this, lang, "actions")
            }
        }
    }

    /**
     * One action of the browser: description, usage, tier and permission
     * node, plus the run button.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param name the action name.
     */
    fun actionDetail(b: MessageBuilder, lang: String?, name: String) {
        val descriptor = bot.catalog.find(name)
        b.v2()
        b.container {
            if (descriptor == null) {
                accentColor = DANGER
                textDisplay { content = texts.tl(lang, "ui.actions.unknown", "action" to name) }
                return@container
            }
            val tier = ActionTiers.classify(name, bot.config())
            accentColor = NEUTRAL
            textDisplay { content = "## `${descriptor.name}`" }
            textDisplay {
                content = listOf(
                    descriptor.description,
                    "",
                    texts.tl(lang, "ui.actions.usage", "usage" to "`${descriptor.usage}`"),
                    texts.tl(lang, "ui.actions.tier", "tier" to tierLabel(lang, tier)),
                    texts.tl(lang, "ui.actions.node", "node" to "`${PermissionGate.actionNode(name)}`"),
                ).joinToString("\n")
            }
            actionRow {
                interactionButton(ButtonStyle.Primary, id("actrun", name)) {
                    label = texts.tl(lang, "ui.actions.run")
                }
                backButton(this, lang, "actions")
            }
        }
    }

    /**
     * The second-click screen of a destructive or critical action.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param pending the pending confirmation.
     */
    fun confirmScreen(b: MessageBuilder, lang: String?, pending: PendingConfirmation) {
        b.v2()
        b.container {
            accentColor = if (pending.tier == ActionTier.CRITICAL) DANGER else WARN
            textDisplay { content = "## ${texts.tl(lang, "confirm.title")}" }
            textDisplay {
                content = texts.tl(
                    lang,
                    if (pending.tier == ActionTier.CRITICAL) "confirm.critical" else "confirm.destructive",
                    "action" to "`${pending.action}${argsSuffix(pending.arguments)}`",
                    "expected" to "`${pending.expectedText}`",
                    "timeout" to "${bot.config().confirmTimeoutSeconds}",
                )
            }
            actionRow {
                if (pending.tier == ActionTier.CRITICAL) {
                    interactionButton(ButtonStyle.Danger, id("cfcrit", pending.id)) {
                        label = texts.tl(lang, "confirm.button.critical")
                    }
                } else {
                    interactionButton(ButtonStyle.Danger, id("cfok", pending.id)) {
                        label = texts.tl(lang, "confirm.button.confirm")
                    }
                }
                interactionButton(ButtonStyle.Secondary, id("cfno", pending.id)) {
                    label = texts.tl(lang, "confirm.button.cancel")
                }
            }
        }
    }

    /**
     * The result of an executed action.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param action the executed action.
     * @param success whether it succeeded.
     * @param lines the result lines.
     */
    fun resultScreen(b: MessageBuilder, lang: String?, action: String, success: Boolean, lines: List<String>) {
        b.v2()
        b.container {
            accentColor = if (success) SUCCESS else DANGER
            textDisplay {
                content = "## ${texts.tl(lang, if (success) "result.ok" else "result.error", "action" to "`$action`")}"
            }
            textDisplay { content = codeBlock(lines.map(DiscordMessages::stripColors)) }
        }
    }

    /**
     * A plain notice screen.
     *
     * @param b target message builder.
     * @param text the notice text.
     * @param success tone of the accent color.
     */
    fun notice(b: MessageBuilder, text: String, success: Boolean = true) {
        b.v2()
        b.container {
            accentColor = if (success) NEUTRAL else DANGER
            textDisplay { content = text }
        }
    }

    /**
     * The live status board content.
     *
     * @param b target message builder.
     */
    fun statusBoard(b: MessageBuilder) {
        b.v2()
        b.container {
            accentColor = SUCCESS
            textDisplay { content = "## ${texts.t("status.title")}" }
            textDisplay { content = codeBlock(statusLines()) }
            textDisplay { content = "-# ${texts.t("status.updated", "time" to "<t:${bot.now() / 1000}:R>")}" }
        }
    }

    /**
     * The status lines the board renders — also the change-detection key,
     * so the board is only edited when this changes.
     *
     * @return the current status lines.
     */
    fun statusLines(): List<String> {
        val overview = bot.run("platform.overview").lines
        val maintenance = bot.run("proxy.maintenance").lines
        val services = bot.run("service.list").lines
        return overview + maintenance + listOf("") + services
    }

    /**
     * The setup-wizard screen with one channel select per target.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param selected already selected channel ids per target.
     */
    fun setupScreen(b: MessageBuilder, lang: String?, selected: Map<String, String>) {
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "setup.title")}" }
            textDisplay { content = texts.tl(lang, "setup.intro") }
            SETUP_TARGETS.forEach { target ->
                actionRow {
                    channelSelect(id("st", target)) {
                        placeholder = texts.tl(lang, "setup.target.$target") +
                            (selected[target]?.let { " ✓" } ?: "")
                    }
                }
            }
            actionRow {
                interactionButton(ButtonStyle.Success, id("stsave")) {
                    label = texts.tl(lang, "setup.save")
                }
                interactionButton(ButtonStyle.Secondary, id("stcancel")) {
                    label = texts.tl(lang, "setup.cancel")
                }
            }
        }
    }

    /**
     * The profile screen of the whois command and context menu.
     *
     * @param b target message builder.
     * @param lang locale of the interacting user.
     * @param discordName the inspected Discord user's name.
     * @param link the user's link, or `null`.
     */
    fun whoisScreen(b: MessageBuilder, lang: String?, discordName: String, link: DiscordLink?) {
        b.v2()
        b.container {
            accentColor = NEUTRAL
            textDisplay { content = "## ${texts.tl(lang, "whois.title", "user" to discordName)}" }
            if (link == null) {
                textDisplay { content = texts.tl(lang, "whois.notlinked") }
            } else {
                val online = bot.onlinePlayers().any { it.uuid == link.uuid || it.name == link.playerName }
                textDisplay {
                    content = listOf(
                        texts.tl(lang, "whois.player", "player" to link.playerName),
                        "UUID: `${link.uuid}`",
                        texts.tl(lang, "whois.linkedby", "via" to link.linkedBy),
                        texts.tl(lang, "ui.player.online", "state" to onOff(lang, online)),
                    ).joinToString("\n")
                }
            }
        }
    }

    private fun ActionRowBuilder.navButton(module: String, text: String) {
        interactionButton(ButtonStyle.Primary, id("open", module)) { label = text }
    }

    private fun navRow(container: ContainerBuilder, lang: String?, module: String) {
        container.actionRow {
            backButton(this, lang, null)
            refreshButton(this, lang, module)
        }
    }

    private fun backButton(row: ActionRowBuilder, lang: String?, module: String?) {
        row.interactionButton(ButtonStyle.Secondary, if (module == null) id("home") else id("open", module)) {
            label = texts.tl(lang, if (module == null) "ui.nav.overview" else "ui.nav.back")
        }
    }

    private fun refreshButton(row: ActionRowBuilder, lang: String?, module: String) {
        row.interactionButton(ButtonStyle.Secondary, id("open", module)) {
            label = texts.tl(lang, "ui.nav.refresh")
        }
    }

    private fun tierLabel(lang: String?, tier: ActionTier): String =
        texts.tl(lang, "tier.${tier.name.lowercase()}")

    private fun onOff(lang: String?, state: Boolean): String =
        texts.tl(lang, if (state) "ui.yes" else "ui.no")

    private fun codeBlock(lines: List<String>): String {
        val body = lines.ifEmpty { listOf("—") }
            .joinToString("\n") { DiscordMessages.stripColors(it) }
        return "```\n${DiscordMessages.truncate(body, TEXT_LIMIT - 10)}\n```"
    }

    private fun argsSuffix(arguments: List<String>): String =
        if (arguments.isEmpty()) "" else " " + arguments.joinToString(" ")

    companion object {
        /** Separator of custom-id segments; never occurs in the payloads. */
        const val SEP = "|"

        /** Prefix of every custom id owned by this bot. */
        const val PREFIX = "hx"

        /** Discord's select-menu option limit. */
        const val SELECT_LIMIT = 25

        /** Conservative text-display length limit. */
        const val TEXT_LIMIT = 3600

        /** Wizard targets, each mapped to one config channel field. */
        val SETUP_TARGETS = listOf("panel", "status", "audit", "notify")

        private val NEUTRAL = Color(0x5865F2)
        private val SUCCESS = Color(0x57F287)
        private val DANGER = Color(0xED4245)
        private val WARN = Color(0xFEE75C)

        /**
         * Builds a custom id out of segments.
         *
         * @param parts route and payload segments.
         * @return the joined custom id.
         */
        fun id(vararg parts: String): String = (listOf(PREFIX) + parts).joinToString(SEP)

        /**
         * Splits a custom id back into its segments.
         *
         * @param customId the component custom id.
         * @return the segments after the prefix, or `null` for foreign ids.
         */
        fun parse(customId: String): List<String>? {
            val parts = customId.split(SEP)
            return if (parts.firstOrNull() == PREFIX) parts.drop(1) else null
        }

        /**
         * Marks a message as Components V2.
         */
        fun MessageBuilder.v2() {
            messageFlags { +MessageFlag.IsComponentsV2 }
        }
    }
}
