package de.tytoss.iguard.gui

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.texture.GuiTextureDefinition
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
import net.kyori.adventure.text.minimessage.MiniMessage
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
    private val scope: CoroutineScope,
    private val translations: NodeTranslations
) {
    private val miniMessage = MiniMessage.miniMessage()

    private data class PanelData(
        var flagged: List<IncidentSnapshot>,
        var selected: String?,
        var selectedIncident: UUID? = null,
        var bans: List<de.tytoss.iguard.storage.BanRow> = emptyList()
    )

    // Ban reason/duration templates shown in the panel's ban submenu (label key; hours; 8760 = 1y ≈
    // "permanent"). The reason is the canonical, stored ban reason (not player-language localized).
    private val banTemplates = listOf(
        Triple("panel.template.day", 24, "Cheating"),
        Triple("panel.template.week", 168, "Cheating"),
        Triple("panel.template.month", 720, "Cheating"),
        Triple("panel.template.permanent", 8760, "Cheating (permanent)")
    )

    private val state = ConcurrentHashMap<UUID, PanelData>()
    @Volatile private var igui: IGui? = null
    @Volatile private var panel: GuiDefinition? = null
    private val font = Key.key("minecraft", "default")

    /** Awaits the shared Helix-GUIs instance and builds the panel definition; safe to call once on enable. */
    fun install() {
        scope.launch {
            val gui = awaitSharedIGui()
            // Custom UI glyphs from IGuardPack: the slim header bar and the full-window dark
            // background (header baked in + slot frames), both drawn in the GUI title. Registered
            // here (not in an install{} block) since IGui itself is installed once, shared, by the
            // Helix-GUIs plugin \u2014 every addon contributing its own textures registers them the same
            // way, into the one database that plugin owns.
            gui.saveTexture(
                GuiTextureDefinition("header", "\uE001", Key.key("iguard", "ui"), 176, 18, 177),
            )
            gui.saveTexture(
                GuiTextureDefinition("background", "\uE003", Key.key("iguard", "ui"), 176, 222, 177),
            )
            igui = gui
            panel = buildPanel(gui)
            plugin.logger.info("IGuard admin panel (IGui) ready")
        }
    }

    private fun headerTexture() = runCatching { igui?.cachedTexture("header") }.getOrNull()
    private fun backgroundTexture() = runCatching { igui?.cachedTexture("background") }.getOrNull()

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
        scope.launch { definition.open(admin, if (focus != null) "detail" else "main") }
    }

    private suspend fun buildPanel(gui: IGui): GuiDefinition = gui.gui("iguard-panel") {
        rows = 6
        landingPage = "main"

        page("main") {
            cancelAllInteractions = true
            permission = "iguard.panel"
            title { ctx ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText(screenText(ctx.player, "panel.title.alerts"), 0, color = NamedTextColor.WHITE)
            }
            prepare { player -> state.computeIfAbsent(player.uniqueId) { PanelData(emptyList(), null) }.flagged = engine.recentIncidents() }
            for (index in 0..44) {
                item(index) { ctx -> state[ctx.player.uniqueId]?.flagged?.getOrNull(index)?.let { flaggedHead(ctx.player, it) } }
                onClick(index) { ctx ->
                    val data = state[ctx.player.uniqueId] ?: return@onClick
                    val snap = data.flagged.getOrNull(index) ?: return@onClick
                    data.selected = snap.playerName
                    data.selectedIncident = snap.incidentId
                    ctx.openPage("detail")
                }
            }
            item(49) { ctx -> controlItem(ctx.player, Material.LIME_DYE, "panel.button.refresh") }
            onClick(49) { ctx -> ctx.gui.refresh(ctx.player) }
            item(51) { ctx -> controlItem(ctx.player, Material.IRON_BARS, "panel.button.active-bans") }
            onClick(51) { ctx -> loadBans(ctx.player) { ctx.openPage("bans") } }
            item(53) { ctx -> controlItem(ctx.player, Material.BARRIER, "panel.button.close") }
            onClick(53) { ctx -> ctx.close() }
        }

        page("bantemplates") {
            cancelAllInteractions = true
            permission = "iguard.ban"
            title { ctx ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText(screenText(ctx.player, "panel.title.ban", "player" to (state[ctx.player.uniqueId]?.selected ?: "")), 0, color = NamedTextColor.WHITE)
            }
            banTemplates.forEachIndexed { i, (labelKey, hours, reason) ->
                val slot = 19 + i
                item(slot) { ctx -> actionItem(ctx.player, Material.RED_DYE, labelKey, "panel.template.tip", "reason" to reason, "hours" to "$hours") }
                onClick(slot) { ctx ->
                    val name = state[ctx.player.uniqueId]?.selected ?: return@onClick
                    Bukkit.getPlayerExact(name)?.let { bans.ban(it.uniqueId, name, hours, "$reason (panel template)", ctx.player.name) }
                        ?: ctx.player.sendMessage(chat(ctx.player, "panel.not-online", "player" to name))
                    ctx.close()
                }
            }
            item(45) { ctx -> controlItem(ctx.player, Material.ARROW, "panel.button.back") }
            onClick(45) { ctx -> ctx.openPage("detail") }
            item(53) { ctx -> controlItem(ctx.player, Material.BARRIER, "panel.button.close") }
            onClick(53) { ctx -> ctx.close() }
        }

        page("bans") {
            cancelAllInteractions = true
            permission = "iguard.ban"
            title { ctx ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText(screenText(ctx.player, "panel.title.bans"), 0, color = NamedTextColor.WHITE)
            }
            for (index in 0..44) {
                item(index) { ctx -> state[ctx.player.uniqueId]?.bans?.getOrNull(index)?.let { banHead(ctx.player, it) } }
                onClick(index) { ctx ->
                    val ban = state[ctx.player.uniqueId]?.bans?.getOrNull(index) ?: return@onClick
                    unbanFromGui(ctx.player, ban) { loadBans(ctx.player) { ctx.gui.refresh(ctx.player) } }
                }
            }
            item(49) { ctx -> controlItem(ctx.player, Material.LIME_DYE, "panel.button.refresh") }
            onClick(49) { ctx -> loadBans(ctx.player) { ctx.gui.refresh(ctx.player) } }
            item(53) { ctx -> controlItem(ctx.player, Material.BARRIER, "panel.button.close") }
            onClick(53) { ctx -> ctx.close() }
        }

        page("detail") {
            cancelAllInteractions = true
            permission = "iguard.panel"
            title { ctx ->
                val bg = backgroundTexture()
                if (bg != null) { centeredTexture(bg); toStart() }
                centeredText(state[ctx.player.uniqueId]?.selected ?: screenText(ctx.player, "panel.title.case"), 0, color = NamedTextColor.WHITE)
            }
            item(4) { ctx -> state[ctx.player.uniqueId]?.selected?.let { caseInfo(ctx.player, it) } }
            item(19) { ctx -> actionItem(ctx.player, Material.ENDER_EYE, "panel.action.spectate", "panel.action.spectate.tip") }
            onClick(19) { ctx -> runOnMain(ctx.player) { admin -> target(ctx)?.let { spectate.start(admin, it) } }; ctx.close() }
            item(21) { ctx -> actionItem(ctx.player, Material.ENDER_PEARL, "panel.action.teleport", "panel.action.teleport.tip") }
            onClick(21) { ctx -> runOnMain(ctx.player) { admin -> target(ctx)?.let { admin.teleport(it.location) } }; ctx.close() }
            item(23) { ctx -> actionItem(ctx.player, Material.CLOCK, "panel.action.exempt", "panel.action.exempt.tip") }
            onClick(23) { ctx -> target(ctx)?.let { exemptions.exempt(it.uniqueId, Duration.ofMinutes(5), "admin-panel") } }
            item(25) { ctx -> actionItem(ctx.player, Material.BARRIER, "panel.action.ban", "panel.action.ban.tip") }
            onClick(25) { ctx -> ctx.openPage("bantemplates") }
            item(31) { ctx -> actionItem(ctx.player, Material.FILLED_MAP, "panel.action.replay", "panel.action.replay.tip") }
            onClick(31) { ctx ->
                val incident = state[ctx.player.uniqueId]?.selectedIncident
                if (incident == null) { ctx.player.sendMessage(chat(ctx.player, "panel.replay.none")); return@onClick }
                runOnMain(ctx.player) { admin -> replay.startReplay(admin, incident, 1.0) }
                ctx.close()
            }
            item(45) { ctx -> controlItem(ctx.player, Material.ARROW, "panel.button.back") }
            onClick(45) { ctx -> ctx.openPage("main") }
            item(53) { ctx -> controlItem(ctx.player, Material.BARRIER, "panel.button.close") }
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
            if (admin.isOnline) { admin.sendMessage(chat(admin, "panel.unbanned", "player" to ban.playerName)); then() }
        }
    }

    private fun banHead(player: Player, ban: de.tytoss.iguard.storage.BanRow): ItemStack {
        val until = ban.expiresAt?.let { java.time.Instant.ofEpochMilli(it).toString() }
            ?: screenText(player, "ban.expiry.permanent")
        return head(ban.playerName).apply {
            editMeta { meta ->
                meta.displayName(plain(ban.playerName, NamedTextColor.RED))
                meta.lore(listOf(
                    screen(player, "panel.ban.until", "until" to until),
                    screen(player, "panel.ban.reason", "reason" to ban.reason),
                    screen(player, "panel.ban.click-unban")
                ))
            }
        }
    }

    private fun runOnMain(player: Player, action: (Player) -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) action(player) })
    }

    private fun flaggedHead(player: Player, snap: IncidentSnapshot): ItemStack {
        val confPct = (snap.confidence * 100).toInt()
        val colorTag = if (confPct >= 80) "<red>" else if (confPct >= 50) "<gold>" else "<yellow>"
        return head(snap.playerName).apply {
            editMeta { meta ->
                meta.displayName(plain(snap.playerName, NamedTextColor.WHITE))
                meta.lore(listOf(
                    screen(player, "panel.head.confidence", "color" to colorTag, "value" to "$confPct"),
                    screen(player, "panel.head.families", "families" to snap.families.joinToString()),
                    if (snap.shadowAction != null) screen(player, "panel.head.action", "action" to snap.shadowAction!!)
                    else screen(player, "panel.head.below-threshold"),
                    screen(player, "panel.head.click-case")
                ))
            }
        }
    }

    private fun caseInfo(viewer: Player, name: String): ItemStack {
        val player = Bukkit.getPlayerExact(name)
        val snap = player?.let { engine.incidentSnapshot(it.uniqueId) }
        val vls = player?.let { engine.snapshot(it.uniqueId)?.violationLevels } ?: emptyMap()
        val lore = ArrayList<Component>()
        if (snap != null) {
            lore += screen(viewer, "panel.case.confidence", "value" to "${(snap.confidence * 100).toInt()}")
            lore += screen(viewer, "panel.head.families", "families" to snap.families.joinToString())
            lore += screen(viewer, "panel.case.evidence", "count" to "${snap.evidenceCount}")
            lore += screen(viewer, "panel.case.action", "action" to (snap.shadowAction ?: screenText(viewer, "value.none")))
        } else {
            lore += screen(viewer, "panel.case.no-incident")
        }
        lore += plain(" ", NamedTextColor.DARK_GRAY)
        lore += screen(viewer, "panel.case.vls-header")
        vls.entries.sortedByDescending { it.value }.take(6).forEach {
            lore += screen(viewer, "panel.case.vl-entry", "check" to it.key, "value" to "%.1f".format(it.value))
        }
        return ItemStack(Material.PAPER).apply {
            editMeta { it.displayName(plain(name, NamedTextColor.AQUA)); it.lore(lore) }
        }
    }

    private fun head(name: String): ItemStack = ItemStack(Material.PLAYER_HEAD).apply {
        editMeta { meta -> (meta as? SkullMeta)?.let { s -> Bukkit.getPlayerExact(name)?.let { s.owningPlayer = it } } }
    }

    private fun controlItem(player: Player, material: Material, labelKey: String): ItemStack =
        ItemStack(material).apply { editMeta { it.displayName(screen(player, labelKey)) } }

    private fun actionItem(player: Player, material: Material, labelKey: String, tipKey: String, vararg tipParams: Pair<String, String>): ItemStack =
        ItemStack(material).apply {
            editMeta {
                it.displayName(screen(player, labelKey))
                it.lore(listOf(screen(player, tipKey, *tipParams)))
            }
        }

    private fun pane(): ItemStack = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply { editMeta { it.displayName(Component.empty()) } }

    private fun plain(text: String, color: NamedTextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun locale(player: Player): String = player.locale().language

    /** A chat message (network prefix included) resolved in the player's language, rendered. */
    private fun chat(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.text(player.name, locale(player), key, *params))

    /** Prefix-free GUI text (item names, lore) resolved in the player's language, rendered non-italic. */
    private fun screen(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.screen(player.name, locale(player), key, *params))

    /** Prefix-free GUI text as a plain string, for the centered pixel-font titles. */
    private fun screenText(player: Player, key: String, vararg params: Pair<String, String>): String =
        translations.screen(player.name, locale(player), key, *params)

    private fun render(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text)).decoration(TextDecoration.ITALIC, false)
}
