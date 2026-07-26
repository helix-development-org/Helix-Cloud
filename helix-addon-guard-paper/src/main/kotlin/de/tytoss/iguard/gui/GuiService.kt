package de.tytoss.iguard.gui

import de.tytoss.igui.IGui
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.check.Enforcement
import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.model.IncidentSnapshot
import de.tytoss.iguard.spectate.SpectateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Admin panel built on the IGui library: a live grid of flagged players and a per-player detail page
 * with one-click moderation actions (spectate / teleport / ban / exempt). IGui is installed lazily and
 * the definition is opened per admin; dynamic item renderers read a per-admin snapshot taken in prepare.
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
    private val scope: CoroutineScope
) {
    private data class PanelData(
        var flagged: List<IncidentSnapshot>,
        var selected: String?,
        var selectedIncident: UUID? = null,
        var bans: List<de.tytoss.iguard.storage.BanRow> = emptyList()
    )

    // Ban reason/duration templates shown in the panel's ban submenu (hours; 8760 = 1y ≈ "permanent").
    private val banTemplates = listOf(
        Triple("Cheating — 1 day", 24, "Cheating"),
        Triple("Cheating — 7 days", 168, "Cheating"),
        Triple("Cheating — 30 days", 720, "Cheating"),
        Triple("Cheating — permanent", 8760, "Cheating (permanent)")
    )

    private val state = ConcurrentHashMap<UUID, PanelData>()
    @Volatile private var igui: IGui? = null
    @Volatile private var panel: GuiDefinition? = null
    private val font = Key.key("minecraft", "default")

    /** Installs IGui asynchronously and builds the panel definition; safe to call once on enable. */
    fun install() {
        scope.launch {
            val gui = IGui.install(plugin) {
                // Our own resource pack lives under the "iguard" namespace (assets/iguard/font/*).
                fonts = de.tytoss.igui.display.GuiFontConfiguration(namespace = "iguard")
                // There is no database in the Helix world: IGui's texture store lives in a JSON file.
                database(FileGuiTextureDatabase(plugin.dataFolder.toPath().resolve("gui-textures.json")))
                // Custom UI glyphs from IGuardPack: the slim header bar and the full-window dark
                // background (header baked in + slot frames), both drawn in the GUI title.
                texture("header", "\uE001", "ui", widthPixels = 176, heightPixels = 18, advancePixels = 177)
                texture("background", "\uE003", "ui", widthPixels = 176, heightPixels = 222, advancePixels = 177)
            }
            igui = gui
            panel = buildPanel(gui)
            plugin.logger.info("IGuard admin panel (IGui) ready")
        }
    }

    private fun headerTexture() = runCatching { igui?.cachedTexture("header") }.getOrNull()
    private fun backgroundTexture() = runCatching { igui?.cachedTexture("background") }.getOrNull()

    /** Shuts IGui down asynchronously (closes open panels); called from onDisable. */
    fun shutdown() {
        val gui = igui ?: return
        scope.launch { runCatching { gui.shutdown() } }
    }

    /** Open the panel; [focus] optionally jumps straight to a player's detail page. */
    fun open(admin: Player, focus: String? = null) {
        val definition = panel ?: run {
            admin.sendMessage(Component.text("Admin panel is still starting, try again in a moment.", NamedTextColor.RED))
            return
        }
        val focusIncident = focus?.let { Bukkit.getPlayerExact(it)?.uniqueId?.let(engine::incidentSnapshot)?.incidentId }
        state[admin.uniqueId] = PanelData(engine.recentIncidents(), focus, focusIncident)
        scope.launch { definition.open(admin, if (focus != null) "detail" else "main") }
    }

    private suspend fun buildPanel(gui: IGui): GuiDefinition = gui.gui("iguard-panel") {
        rows = 6
        landingPage = "main"

        page("main") {
            cancelAllInteractions = true
            permission = "iguard.panel"
            title { _ ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText("IGuard  Live Alerts", 0, color = NamedTextColor.WHITE)
            }
            prepare { player -> state.computeIfAbsent(player.uniqueId) { PanelData(emptyList(), null) }.flagged = engine.recentIncidents() }
            for (index in 0..44) {
                item(index) { ctx -> state[ctx.player.uniqueId]?.flagged?.getOrNull(index)?.let(::flaggedHead) }
                onClick(index) { ctx ->
                    val data = state[ctx.player.uniqueId] ?: return@onClick
                    val snap = data.flagged.getOrNull(index) ?: return@onClick
                    data.selected = snap.playerName
                    data.selectedIncident = snap.incidentId
                    ctx.openPage("detail")
                }
            }
            item(49, controlItem(Material.LIME_DYE, "Refresh", NamedTextColor.GREEN))
            onClick(49) { ctx -> ctx.gui.refresh(ctx.player) }
            item(51, controlItem(Material.IRON_BARS, "Active Bans", NamedTextColor.RED))
            onClick(51) { ctx -> loadBans(ctx.player) { ctx.openPage("bans") } }
            item(53, controlItem(Material.BARRIER, "Close", NamedTextColor.RED))
            onClick(53) { ctx -> ctx.close() }
        }

        page("bantemplates") {
            cancelAllInteractions = true
            permission = "iguard.ban"
            title { ctx ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText("Ban ${state[ctx.player.uniqueId]?.selected ?: ""}", 0, color = NamedTextColor.WHITE)
            }
            banTemplates.forEachIndexed { i, (label, hours, reason) ->
                val slot = 19 + i
                item(slot, actionItem(Material.RED_DYE, label, NamedTextColor.RED, "$reason • ${hours}h"))
                onClick(slot) { ctx ->
                    val name = state[ctx.player.uniqueId]?.selected ?: return@onClick
                    Bukkit.getPlayerExact(name)?.let { bans.ban(it.uniqueId, name, hours, "$reason (panel template)", ctx.player.name) }
                        ?: ctx.player.sendMessage(plain("$name is not online", NamedTextColor.RED))
                    ctx.close()
                }
            }
            item(45, controlItem(Material.ARROW, "Back", NamedTextColor.GRAY))
            onClick(45) { ctx -> ctx.openPage("detail") }
            item(53, controlItem(Material.BARRIER, "Close", NamedTextColor.RED))
            onClick(53) { ctx -> ctx.close() }
        }

        page("bans") {
            cancelAllInteractions = true
            permission = "iguard.ban"
            title { _ ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText("Active Bans", 0, color = NamedTextColor.WHITE)
            }
            for (index in 0..44) {
                item(index) { ctx -> state[ctx.player.uniqueId]?.bans?.getOrNull(index)?.let(::banHead) }
                onClick(index) { ctx ->
                    val ban = state[ctx.player.uniqueId]?.bans?.getOrNull(index) ?: return@onClick
                    unbanFromGui(ctx.player, ban) { loadBans(ctx.player) { ctx.gui.refresh(ctx.player) } }
                }
            }
            item(49, controlItem(Material.LIME_DYE, "Refresh", NamedTextColor.GREEN))
            onClick(49) { ctx -> loadBans(ctx.player) { ctx.gui.refresh(ctx.player) } }
            item(53, controlItem(Material.BARRIER, "Close", NamedTextColor.RED))
            onClick(53) { ctx -> ctx.close() }
        }

        page("detail") {
            cancelAllInteractions = true
            permission = "iguard.panel"
            title { ctx ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText(state[ctx.player.uniqueId]?.selected ?: "Case", 0, color = NamedTextColor.WHITE)
            }
            item(4) { ctx -> state[ctx.player.uniqueId]?.selected?.let(::caseInfo) }
            item(19, actionItem(Material.ENDER_EYE, "Spectate", NamedTextColor.AQUA, "Watch this player live"))
            onClick(19) { ctx -> runOnMain(ctx.player) { admin -> target(ctx)?.let { spectate.start(admin, it) } }; ctx.close() }
            item(21, actionItem(Material.ENDER_PEARL, "Teleport", NamedTextColor.LIGHT_PURPLE, "Teleport to this player"))
            onClick(21) { ctx -> runOnMain(ctx.player) { admin -> target(ctx)?.let { admin.teleport(it.location) } }; ctx.close() }
            item(23, actionItem(Material.CLOCK, "Exempt 5m", NamedTextColor.YELLOW, "Whitelist this player for 5 minutes"))
            onClick(23) { ctx -> target(ctx)?.let { exemptions.exempt(it.uniqueId, Duration.ofMinutes(5), "admin-panel") } }
            item(25, actionItem(Material.BARRIER, "Ban ▾", NamedTextColor.RED, "Choose a ban template"))
            onClick(25) { ctx -> ctx.openPage("bantemplates") }
            item(31, actionItem(Material.FILLED_MAP, "View Replay", NamedTextColor.GOLD, "Watch a rebuilt replay of this case"))
            onClick(31) { ctx ->
                val incident = state[ctx.player.uniqueId]?.selectedIncident
                if (incident == null) { ctx.player.sendMessage(Component.text("No replay recorded for this case.", NamedTextColor.RED)); return@onClick }
                runOnMain(ctx.player) { admin -> replay.startReplay(admin, incident, 1.0) }
                ctx.close()
            }
            item(45, controlItem(Material.ARROW, "Back", NamedTextColor.GRAY))
            onClick(45) { ctx -> ctx.openPage("main") }
            item(53, controlItem(Material.BARRIER, "Close", NamedTextColor.RED))
            onClick(53) { ctx -> ctx.close() }
        }

        onClose { ctx -> state.remove(ctx.player.uniqueId) }
    }

    private fun target(ctx: de.tytoss.igui.gui.GuiClickContext): Player? =
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
            if (admin.isOnline) { admin.sendMessage(plain("Unbanned ${ban.playerName}", NamedTextColor.GREEN)); then() }
        }
    }

    private fun banHead(ban: de.tytoss.iguard.storage.BanRow): ItemStack {
        val until = ban.expiresAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "permanent"
        return head(ban.playerName).apply {
            editMeta { meta ->
                meta.displayName(plain(ban.playerName, NamedTextColor.RED))
                meta.lore(listOf(
                    plain("Until: $until", NamedTextColor.GRAY),
                    plain("Reason: ${ban.reason}", NamedTextColor.DARK_GRAY),
                    plain("Click to unban", NamedTextColor.GREEN)
                ))
            }
        }
    }

    private fun runOnMain(player: Player, action: (Player) -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) action(player) })
    }

    private fun flaggedHead(snap: IncidentSnapshot): ItemStack {
        val confPct = (snap.confidence * 100).toInt()
        val color = if (confPct >= 80) NamedTextColor.RED else if (confPct >= 50) NamedTextColor.GOLD else NamedTextColor.YELLOW
        return head(snap.playerName).apply {
            editMeta { meta ->
                meta.displayName(plain(snap.playerName, NamedTextColor.WHITE))
                meta.lore(listOf(
                    plain("Confidence: $confPct%", color),
                    plain("Families: ${snap.families.joinToString()}", NamedTextColor.GRAY),
                    plain(if (snap.shadowAction != null) "Action: ${snap.shadowAction}" else "Below threshold", NamedTextColor.DARK_GRAY),
                    plain("Click to open case", NamedTextColor.DARK_AQUA)
                ))
            }
        }
    }

    private fun caseInfo(name: String): ItemStack {
        val player = Bukkit.getPlayerExact(name)
        val snap = player?.let { engine.incidentSnapshot(it.uniqueId) }
        val vls = player?.let { engine.snapshot(it.uniqueId)?.violationLevels } ?: emptyMap()
        val lore = ArrayList<Component>()
        if (snap != null) {
            lore += plain("Confidence: ${(snap.confidence * 100).toInt()}%", NamedTextColor.GOLD)
            lore += plain("Families: ${snap.families.joinToString()}", NamedTextColor.GRAY)
            lore += plain("Evidence count: ${snap.evidenceCount}", NamedTextColor.GRAY)
            lore += plain("Action: ${snap.shadowAction ?: "none"}", NamedTextColor.LIGHT_PURPLE)
        } else {
            lore += plain("No active incident", NamedTextColor.GRAY)
        }
        lore += plain(" ", NamedTextColor.DARK_GRAY)
        lore += plain("Violation levels:", NamedTextColor.WHITE)
        vls.entries.sortedByDescending { it.value }.take(6).forEach { lore += plain("  ${it.key}: %.1f".format(it.value), NamedTextColor.DARK_GRAY) }
        return ItemStack(Material.PAPER).apply {
            editMeta { it.displayName(plain(name, NamedTextColor.AQUA)); it.lore(lore) }
        }
    }

    private fun head(name: String): ItemStack = ItemStack(Material.PLAYER_HEAD).apply {
        editMeta { meta -> (meta as? SkullMeta)?.let { s -> Bukkit.getPlayerExact(name)?.let { s.owningPlayer = it } } }
    }

    private fun controlItem(material: Material, label: String, color: NamedTextColor): ItemStack =
        ItemStack(material).apply { editMeta { it.displayName(plain(label, color)) } }

    private fun actionItem(material: Material, label: String, color: NamedTextColor, tip: String): ItemStack =
        ItemStack(material).apply { editMeta { it.displayName(plain(label, color)); it.lore(listOf(plain(tip, NamedTextColor.GRAY))) } }

    private fun pane(): ItemStack = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply { editMeta { it.displayName(Component.empty()) } }

    private fun plain(text: String, color: NamedTextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)
}
