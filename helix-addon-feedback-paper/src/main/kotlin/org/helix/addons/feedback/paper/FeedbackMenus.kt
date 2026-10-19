package org.helix.addons.feedback.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiInputCancelledException
import de.tytoss.igui.gui.GuiInputTimeoutException
import de.tytoss.igui.screen.ButtonStyle
import de.tytoss.igui.screen.LedgerButton
import de.tytoss.igui.screen.LedgerEmpty
import de.tytoss.igui.screen.LedgerLayout
import de.tytoss.igui.screen.LedgerLine
import de.tytoss.igui.screen.LedgerPill
import de.tytoss.igui.screen.LedgerRow
import de.tytoss.igui.screen.LedgerScreen
import de.tytoss.igui.screen.LedgerView
import de.tytoss.igui.screen.LifeSign
import de.tytoss.igui.screen.PillColor
import de.tytoss.igui.screen.Villagers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.WeatherType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The ledger menus of the feedback component on the shared IGui instance: the `/bug` form, `/bugs` (own reports
 * with detail and comments) and `/testtools`. All texts are node translations of `helix.feedback`.
 *
 * @property plugin the owning plugin (scheduler, logging, test tools config).
 * @property client node client.
 * @property capture context capture for new reports.
 * @property scope coroutine scope of the IGui operations (main-thread dispatcher).
 */
class FeedbackMenus(
    private val plugin: FeedbackPlugin,
    private val client: FeedbackNodeClient,
    private val capture: ContextCapture,
    private val scope: CoroutineScope,
) {
    private val ledger = LedgerScreen()
    private val mini = MiniMessage.miniMessage()

    @Volatile private var menu: GuiDefinition? = null
    private val drafts = ConcurrentHashMap<UUID, ReportDraft>()
    private val formErrors = ConcurrentHashMap<UUID, List<String>>()
    private val mine = ConcurrentHashMap<UUID, List<ReportView>>()
    private val details = ConcurrentHashMap<UUID, ReportDetail>()
    private val pages = ConcurrentHashMap<UUID, Int>()
    private val tools = ConcurrentHashMap<UUID, List<ToolRow>>()
    private val toolState = ConcurrentHashMap<UUID, ToolState>()

    /** Awaits the shared IGui instance and builds the menu (call once on enable). */
    fun install() {
        scope.launch {
            val gui = awaitSharedIGui()
            menu = build(gui)
        }
    }

    /** Drops IGui references (call on disable). */
    fun shutdown() {
        menu = null
    }

    /**
     * Opens the bug form with a fresh draft (category guessed from this server's task).
     *
     * @param player the reporter.
     */
    fun openForm(player: Player) {
        drafts[player.uniqueId] = ReportDraft(category = Category.forTask(plugin.task))
        formErrors.remove(player.uniqueId)
        open(player, FORM)
    }

    /**
     * Opens the list of the player's own reports.
     *
     * @param player the player.
     */
    fun openMine(player: Player) {
        pages[player.uniqueId] = 0
        open(player, MINE)
    }

    /**
     * Opens the test tools.
     *
     * @param player the tester.
     */
    fun openTools(player: Player) {
        pages[player.uniqueId] = 0
        open(player, TOOLS)
    }

    private fun open(player: Player, page: String) {
        val definition = menu ?: run {
            player.sendMessage(plugin.chat(player, "menu.starting"))
            return
        }
        scope.launch { definition.open(player, page) }
    }

    private suspend fun build(gui: IGui): GuiDefinition = gui.gui("feedback-menus") {
        rows = 6
        playerInventory = true
        landingPage = FORM
        onClose { ctx -> ledger.forget(ctx.player) }
        page(FORM) {
            prepare { player -> showForm(player) }
            ledger.install(
                this,
                onRow = { ctx, row -> formRow(ctx, row) },
                onButton = { ctx, button -> if (button == 0) ctx.close() else submit(ctx) },
            )
        }
        page(MINE) {
            prepare { player -> loadMine(player) }
            ledger.install(
                this,
                onRow = { ctx, row -> openDetail(ctx, row) },
                onPage = { ctx, step ->
                    pages.merge(ctx.player.uniqueId, step, Int::plus)
                    showMine(ctx.player)
                    ctx.gui.refresh(ctx.player, false)
                },
                onButton = { ctx, button -> if (button == 1) ctx.openPage(FORM).also { openForm(ctx.player) } else ctx.close() },
            )
        }
        page(DETAIL) {
            prepare { player -> showDetail(player) }
            ledger.install(
                this,
                onRow = { _, _ -> },
                onButton = { ctx, button -> if (button == 0) comment(ctx) },
                onBack = { ctx -> ctx.openPage(MINE) },
            )
        }
        page(TOOLS) {
            prepare { player -> loadTools(player) }
            ledger.install(
                this,
                onRow = { ctx, row -> toolRow(ctx, row) },
                onPage = { ctx, step ->
                    pages.merge(ctx.player.uniqueId, step, Int::plus)
                    showTools(ctx.player)
                    ctx.gui.refresh(ctx.player, false)
                },
            )
        }
    }

    // ------------------------------------------------------------------ /bug form

    private fun showForm(player: Player) {
        val draft = drafts.getOrPut(player.uniqueId) { ReportDraft(category = Category.forTask(plugin.task)) }
        val labymod = capture.isLabyMod(player)
        val none = text(player, "form.empty")
        val rows = buildList {
            add(LedgerRow(text(player, "form.category"), text(player, draft.category.langKey), icon = ItemStack(Material.COMPASS)))
            add(
                LedgerRow(
                    text(player, "form.severity"),
                    draft.severity?.let { text(player, it.langKey) } ?: none,
                    valueColor = if (draft.severity == Severity.EXPLOIT) LedgerScreen.RED else LedgerScreen.INK,
                    icon = ItemStack(Material.REDSTONE_TORCH),
                    tooltip = if (draft.severity == Severity.EXPLOIT) listOf(plain(text(player, "form.exploit-hint"), NamedTextColor.RED)) else emptyList(),
                ),
            )
            add(LedgerRow(text(player, "form.title"), draft.title.ifBlank { none }, icon = ItemStack(Material.NAME_TAG)))
            add(LedgerRow(text(player, "form.description"), shorten(draft.description).ifBlank { none }, icon = ItemStack(Material.WRITABLE_BOOK)))
            if (labymod) {
                add(
                    LedgerRow(
                        text(player, "form.screenshot"),
                        text(player, if (draft.screenshot) "menu.on" else "menu.off"),
                        valueColor = if (draft.screenshot) LedgerScreen.GREEN else LedgerScreen.FADED,
                        icon = ItemStack(Material.PAINTING),
                        pill = LedgerPill(text(player, if (draft.screenshot) "menu.on" else "menu.off"), if (draft.screenshot) PillColor.GREEN else PillColor.GREY),
                    ),
                )
            }
        }
        val errors = formErrors[player.uniqueId].orEmpty()
        val detail = buildList {
            add(LedgerLine(text(player, "form.note.title"), LedgerScreen.RED))
            add(LedgerLine(""))
            if (errors.isEmpty()) {
                text(player, "form.note.help").split("\n").forEach { add(LedgerLine(it)) }
            } else {
                errors.forEach { add(LedgerLine(text(player, it), LedgerScreen.RED)) }
            }
            if (draft.severity == Severity.EXPLOIT) {
                add(LedgerLine(""))
                add(LedgerLine(text(player, "form.exploit-hint"), LedgerScreen.RED))
            }
        }
        ledger.show(
            player,
            LedgerView(
                title = text(player, "form.heading"),
                subtitle = text(player, "form.subtitle", "server" to plugin.service),
                rows = rows,
                detail = detail,
                leftButton = LedgerButton(text(player, "button.cancel"), ButtonStyle.SECONDARY),
                rightButton = if (draft.confirming) LedgerButton(text(player, "button.confirm"), ButtonStyle.DANGER)
                else LedgerButton(text(player, "button.send"), ButtonStyle.PRIMARY),
                lifeSign = LifeSign.QUILL,
            ),
        )
    }

    private suspend fun formRow(ctx: GuiClickContext, row: Int) {
        val player = ctx.player
        val draft = drafts[player.uniqueId] ?: return
        val updated = when (row) {
            0 -> draft.copy(category = FormValidation.next(Category.entries, draft.category))
            1 -> draft.copy(severity = FormValidation.next(Severity.entries, draft.severity))
            2 -> readAnvil(ctx, draft.title)?.let { draft.copy(title = FormValidation.clean(it).take(FormValidation.TITLE_MAX)) } ?: draft
            3 -> {
                val typed = readChat(ctx, text(player, "form.description-prompt"))
                drafts[player.uniqueId] = typed?.let { draft.copy(description = FormValidation.clean(it).take(FormValidation.DESCRIPTION_MAX), confirming = false) } ?: draft
                ctx.gui.open(player, FORM)
                return
            }
            4 -> if (capture.isLabyMod(player)) draft.copy(screenshot = !draft.screenshot) else draft
            else -> draft
        }
        drafts[player.uniqueId] = updated.copy(confirming = false)
        formErrors.remove(player.uniqueId)
        showForm(player)
        ctx.gui.refresh(player, false)
    }

    private suspend fun submit(ctx: GuiClickContext) {
        val player = ctx.player
        val draft = drafts[player.uniqueId] ?: return
        val problems = FormValidation.problems(draft)
        if (problems.isNotEmpty()) {
            formErrors[player.uniqueId] = problems
            drafts[player.uniqueId] = draft.copy(confirming = false)
            showForm(player)
            ctx.gui.refresh(player, false)
            return
        }
        if (!draft.confirming) {
            drafts[player.uniqueId] = draft.copy(confirming = true)
            showForm(player)
            ctx.gui.refresh(player, false)
            return
        }
        val context = ContextBuilder.toJson(capture.capture(player, draft.screenshot))
        val reporter = PlayerRef(player.uniqueId.toString(), player.name)
        ctx.close()
        val reply = withContext(Dispatchers.IO) { client.create(reporter, draft, context) }
        if (reply.ok) {
            drafts.remove(player.uniqueId)
            val nr = plugin.json.parseToJsonElement(reply.body ?: "{}").let { (it as? kotlinx.serialization.json.JsonObject)?.get("nr")?.toString() } ?: "?"
            player.sendMessage(plugin.chat(player, "bug.sent", "nr" to nr))
            if (draft.screenshot) plugin.screenshotRequested(player, nr.toLongOrNull())
        } else {
            player.sendMessage(plugin.chat(player, errorKey(reply.code), "seconds" to (reply.detail?.filter(Char::isDigit) ?: "?")))
        }
    }

    // ------------------------------------------------------------------ /bugs

    private suspend fun loadMine(player: Player) {
        val list = withContext(Dispatchers.IO) { client.mine(player.uniqueId) }
        if (list != null) mine[player.uniqueId] = list
        showMine(player, list == null)
    }

    private fun showMine(player: Player, unavailable: Boolean = false) {
        val list = mine[player.uniqueId].orEmpty()
        val pageCount = maxOf(1, (list.size + LedgerLayout.ROWS - 1) / LedgerLayout.ROWS)
        val page = (pages[player.uniqueId] ?: 0).coerceIn(0, pageCount - 1).also { pages[player.uniqueId] = it }
        val rows = list.drop(page * LedgerLayout.ROWS).take(LedgerLayout.ROWS).map { report ->
            val status = ReportStatus.of(report.status)
            LedgerRow(
                label = "#${report.nr} ${report.title}",
                icon = ItemStack(if (Severity.of(report.severity) == Severity.EXPLOIT) Material.TNT else Material.PAPER),
                pill = LedgerPill(text(player, status.langKey), pillOf(status)),
                tooltip = listOf(
                    plain(report.title, GOLD),
                    plain(text(player, Category.of(report.category)?.langKey ?: "category.other"), GREY),
                    plain(text(player, Severity.of(report.severity)?.langKey ?: "severity.minor"), GREY),
                    plain(text(player, "bugs.points", "points" to report.points.toString()), GREY),
                    plain(text(player, "bugs.open-detail"), GOLD),
                ),
            )
        }
        val total = list.sumOf { it.points }
        ledger.show(
            player,
            LedgerView(
                title = text(player, "bugs.heading"),
                subtitle = text(player, "bugs.subtitle", "count" to list.size.toString(), "points" to total.toString()),
                rows = rows,
                page = page,
                pages = pageCount,
                detail = listOf(
                    LedgerLine(text(player, "bugs.note.title"), LedgerScreen.RED),
                    LedgerLine(""),
                    LedgerLine(text(player, "bugs.note.points", "points" to total.toString())),
                    LedgerLine(text(player, if (unavailable) "error.unavailable-short" else "bugs.note.help"), LedgerScreen.FADED),
                ),
                leftButton = LedgerButton(text(player, "button.close"), ButtonStyle.SECONDARY),
                rightButton = LedgerButton(text(player, "button.new"), ButtonStyle.PRIMARY),
                empty = LedgerEmpty(listOf(text(player, "bugs.empty")), Villagers.NARRATOR),
            ),
        )
    }

    private suspend fun openDetail(ctx: GuiClickContext, row: Int) {
        val player = ctx.player
        val index = (pages[player.uniqueId] ?: 0) * LedgerLayout.ROWS + row
        val report = mine[player.uniqueId]?.getOrNull(index) ?: return
        val (detail, reply) = withContext(Dispatchers.IO) { client.get(report.nr, PlayerRef(player.uniqueId.toString(), player.name)) }
        if (detail == null) {
            player.sendMessage(plugin.chat(player, errorKey(reply.code)))
            return
        }
        details[player.uniqueId] = detail
        ctx.openPage(DETAIL)
    }

    private fun showDetail(player: Player) {
        val detail = details[player.uniqueId] ?: return
        val report = detail.report
        val status = ReportStatus.of(report.status)
        val rows = detail.comments.takeLast(LedgerLayout.ROWS).map { comment ->
            LedgerRow(
                label = (if (comment.staff) "★ " else "") + comment.author.name,
                value = shorten(comment.text, COMMENT_SHORT),
                labelColor = if (comment.staff) LedgerScreen.RED else LedgerScreen.INK,
                icon = ItemStack(if (comment.staff) Material.GOLDEN_HELMET else Material.PLAYER_HEAD),
                tooltip = wrap(comment.text).map { plain(it, NamedTextColor.WHITE) },
            )
        }
        val lines = buildList {
            add(LedgerLine("#${report.nr}", LedgerScreen.RED))
            add(LedgerLine(text(player, "detail.status", "status" to text(player, status.langKey))))
            add(LedgerLine(text(player, Category.of(report.category)?.langKey ?: "category.other"), LedgerScreen.FADED))
            add(LedgerLine(text(player, Severity.of(report.severity)?.langKey ?: "severity.minor"), LedgerScreen.FADED))
            add(LedgerLine(""))
            wrap(report.description).take(DESCRIPTION_LINES).forEach { add(LedgerLine(it)) }
        }
        ledger.show(
            player,
            LedgerView(
                title = shorten(report.title, TITLE_SHORT),
                subtitle = text(player, "detail.comments", "count" to detail.comments.size.toString()),
                rows = rows,
                detail = lines,
                back = true,
                leftButton = LedgerButton(text(player, "button.comment"), ButtonStyle.SECONDARY),
                empty = LedgerEmpty(listOf(text(player, "detail.no-comments")), Villagers.NARRATOR),
            ),
        )
    }

    private suspend fun comment(ctx: GuiClickContext) {
        val player = ctx.player
        val report = details[player.uniqueId]?.report ?: return
        val typed = readChat(ctx, text(player, "detail.comment-prompt"))?.let(FormValidation::clean)?.takeIf { it.isNotBlank() }
        if (typed != null) {
            val reply = withContext(Dispatchers.IO) { client.comment(report.nr, PlayerRef(player.uniqueId.toString(), player.name), typed.take(FormValidation.DESCRIPTION_MAX)) }
            if (reply.ok) {
                val (fresh, _) = withContext(Dispatchers.IO) { client.get(report.nr, PlayerRef(player.uniqueId.toString(), player.name)) }
                if (fresh != null) details[player.uniqueId] = fresh
                player.sendMessage(plugin.chat(player, "detail.commented"))
            } else {
                player.sendMessage(plugin.chat(player, errorKey(reply.code)))
            }
        }
        ctx.gui.open(player, DETAIL)
    }

    // ------------------------------------------------------------------ /testtools

    private fun loadTools(player: Player) {
        val config = plugin.toolsConfig
        val state = toolState.getOrPut(player.uniqueId) { ToolState() }
        val list = buildList {
            add(ToolRow.Time)
            add(ToolRow.Weather)
            add(ToolRow.Fly)
            add(ToolRow.Kronen)
            config.points.keys.sorted().forEach { add(ToolRow.Point(it)) }
            val event = TestToolEvent(player)
            plugin.server.pluginManager.callEvent(event)
            event.entries.forEach { add(ToolRow.Extra(it)) }
        }
        tools[player.uniqueId] = list
        toolState[player.uniqueId] = state
        showTools(player)
    }

    private fun showTools(player: Player) {
        val list = tools[player.uniqueId].orEmpty()
        val state = toolState[player.uniqueId] ?: ToolState()
        val pageCount = maxOf(1, (list.size + LedgerLayout.ROWS - 1) / LedgerLayout.ROWS)
        val page = (pages[player.uniqueId] ?: 0).coerceIn(0, pageCount - 1).also { pages[player.uniqueId] = it }
        val flyAllowed = TestToolRules.flyAllowed(plugin.toolsConfig, player.world.name)
        val rows = list.drop(page * LedgerLayout.ROWS).take(LedgerLayout.ROWS).map { tool ->
            when (tool) {
                ToolRow.Time -> LedgerRow(text(player, "tools.time"), text(player, "tools.time.${state.timeIndex}"), icon = ItemStack(Material.CLOCK))
                ToolRow.Weather -> LedgerRow(text(player, "tools.weather"), text(player, "tools.weather.${state.weatherIndex}"), icon = ItemStack(Material.WATER_BUCKET))
                ToolRow.Fly -> LedgerRow(
                    text(player, "tools.fly"),
                    text(player, if (!flyAllowed) "tools.fly.blocked" else if (player.allowFlight) "menu.on" else "menu.off"),
                    valueColor = if (!flyAllowed) LedgerScreen.FADED else if (player.allowFlight) LedgerScreen.GREEN else LedgerScreen.INK,
                    icon = ItemStack(Material.FEATHER),
                )
                ToolRow.Kronen -> LedgerRow(
                    text(player, "tools.kronen"),
                    text(player, "tools.kronen.value", "amount" to plugin.toolsConfig.kronenAmount.toString()),
                    icon = ItemStack(Material.GOLD_NUGGET),
                )
                is ToolRow.Point -> LedgerRow(text(player, "tools.point", "name" to tool.name), icon = ItemStack(Material.ENDER_PEARL))
                is ToolRow.Extra -> LedgerRow(tool.entry.label, icon = tool.entry.icon)
            }
        }
        ledger.show(
            player,
            LedgerView(
                title = text(player, "tools.heading"),
                subtitle = text(player, "tools.subtitle", "server" to plugin.service),
                rows = rows,
                page = page,
                pages = pageCount,
                detail = text(player, "tools.note").split("\n").map { LedgerLine(it) },
                lifeSign = LifeSign.CANDLE,
            ),
        )
    }

    private suspend fun toolRow(ctx: GuiClickContext, row: Int) {
        val player = ctx.player
        if (!TestToolRules.available(plugin.testphase, player.hasPermission(FeedbackPlugin.PERMISSION_TOOLS))) {
            ctx.close()
            player.sendMessage(plugin.chat(player, "tools.closed"))
            return
        }
        val index = (pages[player.uniqueId] ?: 0) * LedgerLayout.ROWS + row
        val tool = tools[player.uniqueId]?.getOrNull(index) ?: return
        val state = toolState.getOrPut(player.uniqueId) { ToolState() }
        when (tool) {
            ToolRow.Time -> {
                val next = (state.timeIndex + 1) % TestToolRules.TIMES.size
                toolState[player.uniqueId] = state.copy(timeIndex = next)
                TestToolRules.TIMES[next]?.let { player.setPlayerTime(it, false) } ?: player.resetPlayerTime()
                plugin.logTool(player, "time", next.toString())
            }
            ToolRow.Weather -> {
                val next = (state.weatherIndex + 1) % WEATHERS
                toolState[player.uniqueId] = state.copy(weatherIndex = next)
                when (next) {
                    0 -> player.resetPlayerWeather()
                    1 -> player.setPlayerWeather(WeatherType.CLEAR)
                    else -> player.setPlayerWeather(WeatherType.DOWNFALL)
                }
                plugin.logTool(player, "weather", next.toString())
            }
            ToolRow.Fly -> {
                if (!TestToolRules.flyAllowed(plugin.toolsConfig, player.world.name)) {
                    player.sendMessage(plugin.chat(player, "tools.fly.blocked-chat"))
                } else {
                    player.allowFlight = !player.allowFlight
                    if (!player.allowFlight) player.isFlying = false
                    plugin.logTool(player, "fly", player.allowFlight.toString())
                }
            }
            ToolRow.Kronen -> {
                val config = plugin.toolsConfig
                val reply = withContext(Dispatchers.IO) { client.testKronen(player.uniqueId, config.kronenAmount, config.kronenDailyLimit) }
                player.sendMessage(
                    when {
                        reply.ok -> plugin.chat(player, "tools.kronen.ok", "amount" to config.kronenAmount.toString(), "balance" to (reply.body ?: "?"))
                        reply.code.contains("limit") -> plugin.chat(player, "tools.kronen.limit")
                        else -> plugin.chat(player, "error.unavailable")
                    },
                )
                plugin.logTool(player, "kronen", if (reply.ok) config.kronenAmount.toString() else reply.code)
            }
            is ToolRow.Point -> {
                val point = plugin.toolsConfig.points[tool.name] ?: return
                val world = plugin.server.getWorld(point.world)
                if (world == null) {
                    player.sendMessage(plugin.chat(player, "tools.point.missing", "name" to tool.name))
                } else {
                    ctx.close()
                    player.teleportAsync(org.bukkit.Location(world, point.x, point.y, point.z, point.yaw, point.pitch))
                    plugin.logTool(player, "point", tool.name)
                    return
                }
            }
            is ToolRow.Extra -> {
                val event = TestToolUseEvent(player, tool.entry.id)
                plugin.server.pluginManager.callEvent(event)
                plugin.logTool(player, tool.entry.id, if (event.handled) "ok" else "unhandled")
                if (!event.handled) player.sendMessage(plugin.chat(player, "tools.extra.unhandled"))
            }
        }
        showTools(player)
        ctx.gui.refresh(player, false)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun readAnvil(ctx: GuiClickContext, current: String): String? = try {
        ctx.anvilInput(current, plain(text(ctx.player, "form.title"), NamedTextColor.WHITE))
    } catch (_: GuiInputCancelledException) {
        null
    } catch (_: GuiInputTimeoutException) {
        null
    }

    private suspend fun readChat(ctx: GuiClickContext, prompt: String): String? = try {
        ctx.chatInput(mini.deserialize(prompt))
    } catch (_: GuiInputCancelledException) {
        null
    } catch (_: GuiInputTimeoutException) {
        null
    }

    private fun text(player: Player, key: String, vararg params: Pair<String, String>): String = plugin.screen(player, key, *params)

    private fun plain(value: String, color: TextColor): Component = Component.text(value, color).decoration(TextDecoration.ITALIC, false)

    private fun shorten(value: String, max: Int = VALUE_SHORT): String = if (value.length <= max) value else value.take(max - 1) + "…"

    private fun wrap(value: String, width: Int = WRAP): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        value.split(' ').forEach { word ->
            if (line.isNotEmpty() && line.length + word.length + 1 > width) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private fun pillOf(status: ReportStatus): PillColor = when (status) {
        ReportStatus.NEW -> PillColor.GREY
        ReportStatus.CONFIRMED, ReportStatus.IN_PROGRESS -> PillColor.GOLD
        ReportStatus.FIXED, ReportStatus.CLOSED -> PillColor.GREEN
        ReportStatus.REJECTED, ReportStatus.DUPLICATE, ReportStatus.NOT_A_BUG -> PillColor.RED
    }

    /** Rows of the test tools. */
    private sealed interface ToolRow {
        /** Personal time. */
        data object Time : ToolRow

        /** Personal weather. */
        data object Weather : ToolRow

        /** Fly toggle. */
        data object Fly : ToolRow

        /** Test crowns. */
        data object Kronen : ToolRow

        /** A named test point. */
        data class Point(val name: String) : ToolRow

        /** An entry added by a game mode. */
        data class Extra(val entry: TestToolEntry) : ToolRow
    }

    /** Per-player test tool state. */
    private data class ToolState(val timeIndex: Int = TestToolRules.TIMES.size - 1, val weatherIndex: Int = 0)

    /** Page ids, sizes and colours. */
    companion object {
        /** The bug form page. */
        const val FORM = "form"

        /** The own-reports page. */
        const val MINE = "mine"

        /** The report detail page. */
        const val DETAIL = "detail"

        /** The test tools page. */
        const val TOOLS = "tools"

        private const val VALUE_SHORT = 28
        private const val COMMENT_SHORT = 24
        private const val TITLE_SHORT = 30
        private const val WRAP = 34
        private const val DESCRIPTION_LINES = 9
        private const val WEATHERS = 3
        private val GOLD: TextColor = TextColor.color(0xFFCF4A)
        private val GREY: TextColor = TextColor.color(0xA89A84)

        /**
         * Maps a node error code to a translation key.
         *
         * @param code the machine code of the reply.
         * @return the key of the chat message.
         */
        fun errorKey(code: String): String = when {
            code == "cooldown" -> "error.cooldown"
            code == "daily_limit" -> "error.daily-limit"
            code == "testphase_closed" -> "error.testphase-closed"
            code == "forbidden" -> "error.forbidden"
            code == "not_found" -> "error.not-found"
            code.startsWith("invalid") -> "error.invalid"
            else -> "error.unavailable"
        }
    }
}
