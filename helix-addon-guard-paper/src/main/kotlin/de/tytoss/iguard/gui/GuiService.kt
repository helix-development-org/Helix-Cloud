package de.tytoss.iguard.gui

import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.model.IncidentSnapshot
import de.tytoss.iguard.spectate.SpectateService
import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.screen.LedgerBadge
import de.tytoss.igui.screen.LedgerLayout
import de.tytoss.igui.screen.LedgerLine
import de.tytoss.igui.screen.LedgerRow
import de.tytoss.igui.screen.LedgerScreen
import de.tytoss.igui.screen.LedgerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import org.helix.api.message.LegacyToMini
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Admin panel on the shared medieval ledger ([LedgerScreen]): the live list of flagged players, a
 * per-player case page with one-click moderation actions (spectate / teleport / exempt / ban / replay),
 * the ban templates and the active bans. Each admin's mode and page live in [PanelData]; the ledger
 * view is rebuilt in prepare, so every refresh shows fresh data.
 */
class GuiService(
    private val plugin: JavaPlugin,
    private val engine: CheckEngine,
    private val exemptions: ExemptionManager,
    private val spectate: SpectateService,
    private val replay: de.tytoss.iguard.replay.ReplayService,
    private val bans: de.tytoss.iguard.ban.BanCoordinator,
    private val storage: de.tytoss.iguard.storage.GuardStore,
    private val serverId: String,
    private val scope: CoroutineScope,
    private val translations: NodeTranslations,
) {
    private val miniMessage = MiniMessage.miniMessage()

    private enum class Mode { ALERTS, CASE, TEMPLATES, BANS }

    private data class PanelData(
        var flagged: List<IncidentSnapshot>,
        var selected: String?,
        var selectedIncident: UUID? = null,
        var bans: List<de.tytoss.iguard.storage.BanRow> = emptyList(),
        var mode: Mode = if (selected != null) Mode.CASE else Mode.ALERTS,
        var page: Int = 0,
    )

    // Ban reason/duration templates shown in the panel's ban submenu (label key; hours; 8760 = 1y ≈
    // "permanent"). The reason is the canonical, stored ban reason (not player-language localized).
    private val banTemplates = listOf(
        Triple("panel.template.day", 24, "Cheating"),
        Triple("panel.template.week", 168, "Cheating"),
        Triple("panel.template.month", 720, "Cheating"),
        Triple("panel.template.permanent", 8760, "Cheating (permanent)"),
    )

    private val state = ConcurrentHashMap<UUID, PanelData>()

    @Volatile private var igui: IGui? = null

    @Volatile private var panel: GuiDefinition? = null
    private val ledger = LedgerScreen()

    /** Awaits the shared Helix-GUIs instance and builds the panel definition; safe to call once on enable. */
    fun install() {
        scope.launch {
            val gui = awaitSharedIGui()
            igui = gui
            panel = buildPanel(gui)
            plugin.logger.info("IGuard admin panel (IGui) ready")
        }
    }

    /**
     * Drops this plugin's references to the shared IGui instance; called
     * from onDisable. Does not shut IGui itself down — it is shared with
     * every other addon's menu, and only the owning Helix-GUIs plugin may
     * do that (on its own, later, onDisable).
     */
    fun shutdown() {
        igui = null
        panel = null
    }

    /** Open the panel; [focus] optionally jumps straight to a player's detail page. */
    fun open(admin: Player, focus: String? = null) {
        val definition = panel ?: run {
            admin.sendMessage(chat(admin, "panel.starting"))
            return
        }
        val focusIncident = focus?.let { Bukkit.getPlayerExact(it)?.uniqueId?.let(engine::incidentSnapshot)?.incidentId }
        state[admin.uniqueId] = PanelData(engine.recentIncidents(), focus, focusIncident)
        scope.launch { definition.open(admin, "main") }
    }

    private suspend fun buildPanel(gui: IGui): GuiDefinition = gui.gui("iguard-panel") {
        rows = 6
        playerInventory = true
        landingPage = "main"
        page("main") {
            permission = "iguard.panel"
            prepare { player ->
                val data = state.computeIfAbsent(player.uniqueId) { PanelData(emptyList(), null) }
                if (data.mode == Mode.ALERTS) data.flagged = engine.recentIncidents()
                ledger.show(player, viewOf(player, data))
            }
            ledger.install(
                this,
                onRow = { ctx, row -> onRow(ctx, row) },
                onButton = { ctx, button -> onButton(ctx, button) },
                onPage = { ctx, step ->
                    state[ctx.player.uniqueId]?.let { it.page += step }
                    ctx.gui.refresh(ctx.player)
                },
            )
        }
        onClose { ctx ->
            state.remove(ctx.player.uniqueId)
            ledger.forget(ctx.player)
        }
    }

    private suspend fun show(ctx: GuiClickContext, mode: Mode) {
        state[ctx.player.uniqueId]?.let { it.mode = mode; it.page = 0 }
        ctx.gui.refresh(ctx.player)
    }

    private suspend fun onRow(ctx: GuiClickContext, row: Int) {
        val data = state[ctx.player.uniqueId] ?: return
        when (data.mode) {
            Mode.ALERTS -> {
                val snap = data.flagged.getOrNull(data.page * LedgerLayout.ROWS + row) ?: return
                data.selected = snap.playerName
                data.selectedIncident = snap.incidentId
                show(ctx, Mode.CASE)
            }
            Mode.CASE -> when (row) {
                0 -> { runOnMain(ctx.player) { admin -> target(ctx)?.let { spectate.start(admin, it) } }; ctx.close() }
                1 -> { runOnMain(ctx.player) { admin -> target(ctx)?.let { admin.teleport(it.location) } }; ctx.close() }
                2 -> {
                    target(ctx)?.let { exemptions.exempt(it.uniqueId, Duration.ofMinutes(5), "admin-panel") }
                    ctx.gui.refresh(ctx.player)
                }
                3 -> if (ctx.player.hasPermission("iguard.ban")) show(ctx, Mode.TEMPLATES)
                4 -> {
                    val incident = data.selectedIncident
                    if (incident == null) { ctx.player.sendMessage(chat(ctx.player, "panel.replay.none")); return }
                    runOnMain(ctx.player) { admin -> replay.startReplay(admin, incident, 1.0) }
                    ctx.close()
                }
            }
            Mode.TEMPLATES -> {
                val (_, hours, reason) = banTemplates.getOrNull(row) ?: return
                if (!ctx.player.hasPermission("iguard.ban")) return
                val name = data.selected ?: return
                Bukkit.getPlayerExact(name)?.let { bans.ban(it.uniqueId, name, hours, "$reason (panel template)", ctx.player.name) }
                    ?: ctx.player.sendMessage(chat(ctx.player, "panel.not-online", "player" to name))
                ctx.close()
            }
            Mode.BANS -> {
                if (!ctx.player.hasPermission("iguard.ban")) return
                val ban = data.bans.getOrNull(data.page * LedgerLayout.ROWS + row) ?: return
                unbanFromGui(ctx.player, ban) { loadBans(ctx.player) { ctx.gui.refresh(ctx.player) } }
            }
        }
    }

    private suspend fun onButton(ctx: GuiClickContext, button: Int) {
        val data = state[ctx.player.uniqueId] ?: return
        when (data.mode) {
            Mode.ALERTS -> if (button == 0) {
                if (ctx.player.hasPermission("iguard.ban")) loadBans(ctx.player) { show(ctx, Mode.BANS) }
            } else {
                ctx.gui.refresh(ctx.player)
            }
            Mode.CASE -> if (button == 0) show(ctx, Mode.ALERTS) else ctx.gui.refresh(ctx.player)
            Mode.TEMPLATES -> show(ctx, Mode.CASE)
            Mode.BANS -> if (button == 0) show(ctx, Mode.ALERTS) else loadBans(ctx.player) { ctx.gui.refresh(ctx.player) }
        }
    }

    /** The ledger page of the admin's current mode. */
    private fun viewOf(admin: Player, data: PanelData): LedgerView {
        val refresh = flat(admin, "panel.button.refresh")
        val back = flat(admin, "panel.button.back")
        return when (data.mode) {
            Mode.ALERTS -> paged(
                data,
                data.flagged.map { alertRow(admin, it) },
                LedgerView(
                    title = flat(admin, "panel.title.alerts"),
                    subtitle = if (data.flagged.isEmpty()) flat(admin, "panel.empty")
                    else flat(admin, "panel.subtitle.alerts", "count" to "${data.flagged.size}"),
                    left = note(admin, listOf("panel.subtitle.server" to arrayOf("server" to serverId))),
                    right = help(admin, "main"),
                    buttons = listOf(flat(admin, "panel.button.active-bans"), refresh),
                ),
            )
            Mode.CASE -> {
                val name = data.selected ?: ""
                LedgerView(
                    title = name.ifEmpty { flat(admin, "panel.title.case") },
                    subtitle = flat(admin, "panel.subtitle.case", "player" to name),
                    rows = listOf(
                        action(admin, Material.ENDER_EYE, "panel.action.spectate", LedgerScreen.INK),
                        action(admin, Material.ENDER_PEARL, "panel.action.teleport", LedgerScreen.INK),
                        action(admin, Material.CLOCK, "panel.action.exempt", LedgerScreen.INK),
                        action(admin, Material.BARRIER, "panel.action.ban", LedgerScreen.RED),
                        action(admin, Material.FILLED_MAP, "panel.action.replay", LedgerScreen.INK),
                    ),
                    left = caseNote(admin, name),
                    right = help(admin, "case"),
                    buttons = listOf(back, refresh),
                )
            }
            Mode.TEMPLATES -> LedgerView(
                title = flat(admin, "panel.title.ban", "player" to (data.selected ?: "")),
                subtitle = flat(admin, "panel.subtitle.ban"),
                rows = banTemplates.map { (labelKey, hours, reason) ->
                    LedgerRow(
                        label = flat(admin, labelKey),
                        value = "${hours}h",
                        labelColor = LedgerScreen.RED,
                        valueColor = LedgerScreen.FADED,
                        icon = ItemStack(Material.RED_DYE),
                        tooltip = listOf(screen(admin, labelKey), screen(admin, "panel.template.tip", "reason" to reason, "hours" to "$hours")),
                    )
                },
                left = caseNote(admin, data.selected ?: ""),
                right = help(admin, "ban"),
                buttons = listOf(back, null),
            )
            Mode.BANS -> paged(
                data,
                data.bans.map { banRow(admin, it) },
                LedgerView(
                    title = flat(admin, "panel.title.bans"),
                    subtitle = if (data.bans.isEmpty()) flat(admin, "panel.bans.empty")
                    else flat(admin, "panel.subtitle.bans", "count" to "${data.bans.size}"),
                    left = note(admin, emptyList()),
                    right = help(admin, "bans"),
                    buttons = listOf(back, refresh),
                ),
            )
        }
    }

    private fun paged(data: PanelData, all: List<LedgerRow>, base: LedgerView): LedgerView {
        val pages = maxOf(1, (all.size + LedgerLayout.ROWS - 1) / LedgerLayout.ROWS)
        data.page = data.page.coerceIn(0, pages - 1)
        return base.copy(rows = all.drop(data.page * LedgerLayout.ROWS).take(LedgerLayout.ROWS), page = data.page, pages = pages)
    }

    private fun alertRow(admin: Player, snap: IncidentSnapshot): LedgerRow {
        val confPct = (snap.confidence * 100).toInt()
        val color = if (confPct >= 80) LedgerScreen.RED else if (confPct >= 50) ORANGE else LedgerScreen.INK
        return LedgerRow(
            label = snap.playerName,
            value = "$confPct%",
            valueColor = color,
            icon = head(snap.playerName),
            badge = if (confPct >= 80) LedgerBadge.RUNE else null,
            tooltip = alertTooltip(admin, snap),
        )
    }

    private fun banRow(admin: Player, ban: de.tytoss.iguard.storage.BanRow): LedgerRow {
        val until = ban.expiresAt?.let { DATE.format(java.time.Instant.ofEpochMilli(it)) }
            ?: screenText(admin, "ban.expiry.permanent")
        return LedgerRow(
            label = ban.playerName,
            value = strip(until),
            labelColor = LedgerScreen.RED,
            valueColor = LedgerScreen.FADED,
            icon = head(ban.playerName),
            tooltip = listOf(
                plain(ban.playerName, TextColor.color(0xFF5A4A)),
                screen(admin, "panel.ban.until", "until" to until),
                screen(admin, "panel.ban.reason", "reason" to ban.reason),
                screen(admin, "panel.ban.click-unban"),
            ),
        )
    }

    private fun action(admin: Player, material: Material, key: String, color: TextColor): LedgerRow =
        LedgerRow(
            label = flat(admin, key),
            labelColor = color,
            icon = ItemStack(material),
            tooltip = listOf(screen(admin, key), screen(admin, "$key.tip")),
        )

    private fun note(admin: Player, extra: List<Pair<String, Array<Pair<String, String>>>>): List<LedgerLine> = buildList {
        add(LedgerLine("IGuard", LedgerScreen.RED))
        add(LedgerLine(""))
        add(LedgerLine(admin.name))
        extra.forEach { (key, params) -> add(LedgerLine(flat(admin, key, *params), LedgerScreen.FADED)) }
    }

    /** Left note of a case: confidence, families, evidence, action and the top violation levels. */
    private fun caseNote(admin: Player, name: String): List<LedgerLine> = buildList {
        add(LedgerLine(flat(admin, "panel.title.case"), LedgerScreen.RED))
        add(LedgerLine(""))
        add(LedgerLine(name))
        val player = Bukkit.getPlayerExact(name)
        val snap = player?.let { engine.incidentSnapshot(it.uniqueId) }
        if (snap != null) {
            val confPct = (snap.confidence * 100).toInt()
            add(LedgerLine(flat(admin, "panel.case.confidence", "value" to "$confPct"), if (confPct >= 80) LedgerScreen.RED else LedgerScreen.INK))
            snap.families.forEach { add(LedgerLine(it.toString().lowercase(), LedgerScreen.FADED)) }
            add(LedgerLine(flat(admin, "panel.case.evidence", "count" to "${snap.evidenceCount}"), LedgerScreen.FADED))
            add(LedgerLine(flat(admin, "panel.case.action", "action" to (snap.shadowAction ?: screenText(admin, "value.none"))), LedgerScreen.FADED))
        } else {
            add(LedgerLine(flat(admin, "panel.case.no-incident"), LedgerScreen.FADED))
        }
        val vls = player?.let { engine.snapshot(it.uniqueId)?.violationLevels } ?: emptyMap()
        if (vls.isNotEmpty()) {
            add(LedgerLine(""))
            add(LedgerLine(flat(admin, "panel.case.vls-header")))
            vls.entries.sortedByDescending { it.value }.take(4).forEach {
                add(LedgerLine("${it.key}: ${"%.1f".format(it.value)}", LedgerScreen.FADED))
            }
        }
    }

    private fun help(admin: Player, mode: String): List<LedgerLine> = buildList {
        add(LedgerLine(flat(admin, "panel.help.title"), LedgerScreen.RED))
        add(LedgerLine(""))
        (1..4).forEach { add(LedgerLine(flat(admin, "panel.help.$mode.$it"))) }
    }

    private fun target(ctx: GuiClickContext): Player? =
        state[ctx.player.uniqueId]?.selected?.let { Bukkit.getPlayerExact(it) }

    /** Loads active bans into the admin's panel state, then runs the suspend [then] (open/refresh via IGui). */
    private fun loadBans(admin: Player, then: suspend () -> Unit) {
        scope.launch {
            val bans = runCatching { storage.activeBans() }.getOrDefault(emptyList())
            state.computeIfAbsent(admin.uniqueId) { PanelData(emptyList(), null) }.bans = bans
            if (admin.isOnline) then()
        }
    }

    private fun unbanFromGui(admin: Player, ban: de.tytoss.iguard.storage.BanRow, then: suspend () -> Unit) {
        scope.launch {
            bans.unban(ban.playerId, ban.playerName, admin.name)
            if (admin.isOnline) { admin.sendMessage(chat(admin, "panel.unbanned", "player" to ban.playerName)); then() }
        }
    }

    private fun alertTooltip(player: Player, snap: IncidentSnapshot): List<Component> {
        val confPct = (snap.confidence * 100).toInt()
        val colorTag = if (confPct >= 80) "<#ff5a4a>" else if (confPct >= 50) "<#ffb43a>" else "<#ffcf4a>"
        return listOf(
            plain(snap.playerName, TextColor.color(0xFFCF4A)),
            screen(player, "panel.head.confidence", "color" to colorTag, "value" to "$confPct"),
            screen(player, "panel.head.families", "families" to snap.families.joinToString()),
            if (snap.shadowAction != null) screen(player, "panel.head.action", "action" to snap.shadowAction)
            else screen(player, "panel.head.below-threshold"),
            screen(player, "panel.head.click-case"),
        )
    }

    private fun runOnMain(player: Player, action: (Player) -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) action(player) })
    }

    private fun head(name: String): ItemStack = ItemStack(Material.PLAYER_HEAD).apply {
        editMeta { meta -> (meta as? SkullMeta)?.let { s -> Bukkit.getPlayerExact(name)?.let { s.owningPlayer = it } } }
    }

    private fun plain(text: String, color: TextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun locale(player: Player): String = player.locale().language

    /** A chat message (network prefix included) resolved in the player's language, rendered. */
    private fun chat(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.text(player.name, locale(player), key, *params))

    /** Prefix-free GUI text (item names, lore) resolved in the player's language, rendered non-italic. */
    private fun screen(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.screen(player.name, locale(player), key, *params))

    /** Prefix-free GUI text with formatting, for tooltips and fallbacks. */
    private fun screenText(player: Player, key: String, vararg params: Pair<String, String>): String =
        translations.screen(player.name, locale(player), key, *params)

    /** Prefix-free GUI text as plain ink for the ledger (tags stripped). */
    private fun flat(player: Player, key: String, vararg params: Pair<String, String>): String =
        strip(screenText(player, key, *params))

    private fun strip(text: String): String = PlainTextComponentSerializer.plainText().serialize(render(text))

    private fun render(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text)).decoration(TextDecoration.ITALIC, false)

    private companion object {
        /** Ink for medium confidence. */
        val ORANGE: TextColor = TextColor.color(0x9A5A12)

        val DATE: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(java.time.ZoneId.systemDefault())
    }
}
