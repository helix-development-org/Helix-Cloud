package org.helix.addons.profile.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiInputCancelledException
import de.tytoss.igui.gui.GuiInputTimeoutException
import de.tytoss.igui.screen.LedgerBadge
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
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.addon.ProfileSettingType
import org.helix.api.addon.ProfileView
import org.helix.api.addon.ResolvedSetting
import org.helix.api.i18n.NodeTranslations
import org.helix.api.message.LegacyToMini
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The `/profilemenu` GUI, built on the shared Helix-GUIs plugin's IGui
 * instance as a medieval ledger (helix-gui [LedgerScreen]): one row per setting the profile addon aggregates across
 * every contributing addon, eight per page. Clicking a toggle flips it, clicking a choice cycles
 * to the next unlocked option, clicking free text opens an anvil prompt —
 * every change round-trips through the node's `profile.setting.set` action,
 * so this plugin holds no settings state of its own. All player-facing text
 * resolves through the node's translations (`menu.*` keys of the profile
 * addon's language files), never hardcoded here.
 *
 * @property plugin the owning plugin, for logging.
 * @property client talks to the node on behalf of the menu.
 * @property translations node-backed, per-player-language texts.
 * @property scope coroutine scope IGui operations run on.
 */
class ProfileGuiService(
    private val plugin: JavaPlugin,
    private val client: ProfileNodeClient,
    private val translations: NodeTranslations,
    private val scope: CoroutineScope,
) {
    @Volatile private var igui: IGui? = null

    @Volatile private var menu: GuiDefinition? = null
    private val views = ConcurrentHashMap<UUID, ProfileView>()
    private val pages = ConcurrentHashMap<UUID, Int>()
    private val ledger = LedgerScreen()
    private val miniMessage = MiniMessage.miniMessage()

    /** Awaits the shared Helix-GUIs instance and builds the menu definition; safe to call once on enable. */
    fun install() {
        scope.launch {
            val gui = awaitSharedIGui()
            igui = gui
            menu = buildMenu(gui)
            plugin.logger.info("Profile menu (IGui) ready")
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
        menu = null
    }

    /**
     * Opens the menu for a player.
     *
     * @param player the player to open the menu for.
     */
    fun open(player: Player) {
        val definition = menu ?: run {
            player.sendMessage(chat(player, "menu.starting"))
            return
        }
        scope.launch { definition.open(player, "main") }
    }

    private suspend fun buildMenu(gui: IGui): GuiDefinition = gui.gui("profile-menu") {
        rows = 6
        playerInventory = true
        landingPage = "main"
        onClose { ctx -> ledger.forget(ctx.player); pages.remove(ctx.player.uniqueId) }
        page("main") {
            prepare { player -> refreshView(player) }
            ledger.install(
                this,
                onRow = { ctx, row ->
                    val index = (pages[ctx.player.uniqueId] ?: 0) * LedgerLayout.ROWS + row
                    val setting = views[ctx.player.uniqueId]?.settings?.getOrNull(index) ?: return@install
                    handleClick(ctx, setting)
                },
                onPage = { ctx, step ->
                    pages.merge(ctx.player.uniqueId, step, Int::plus)
                    showLedger(ctx.player)
                    ctx.gui.refresh(ctx.player)
                },
            )
        }
    }

    /** Puts the current profile page into the ledger: one row per setting, the crest note with a short help on the right. */
    private fun showLedger(player: Player) {
        val view = views[player.uniqueId] ?: return
        val settings = view.settings
        val pageCount = maxOf(1, (settings.size + LedgerLayout.ROWS - 1) / LedgerLayout.ROWS)
        val page = (pages[player.uniqueId] ?: 0).coerceIn(0, pageCount - 1).also { pages[player.uniqueId] = it }
        val rows = settings.drop(page * LedgerLayout.ROWS).take(LedgerLayout.ROWS).map { row(player, it) }
        val detail = buildList {
            add(LedgerLine(screenText(player, "menu.note.title"), LedgerScreen.RED))
            add(LedgerLine(""))
            add(LedgerLine(player.name))
            view.info.values.flatten().take(INFO_LINES).forEach { add(LedgerLine("${it.label}: ${it.value}", LedgerScreen.FADED)) }
            if (settings.isNotEmpty()) {
                add(LedgerLine(""))
                add(LedgerLine(screenText(player, "menu.help")))
            }
        }
        ledger.show(
            player,
            LedgerView(
                title = screenText(player, "menu.title"),
                subtitle = screenText(player, "menu.subtitle", "player" to player.name),
                rows = rows,
                page = page,
                pages = pageCount,
                detail = detail,
                lifeSign = LifeSign.CANDLE,
                empty = LedgerEmpty(listOf(screenText(player, "menu.empty-state")), Villagers.NARRATOR),
            ),
        )
    }

    private fun row(player: Player, setting: ResolvedSetting): LedgerRow {
        val on = setting.current == "true"
        val (value, color) = when (val type = setting.descriptor.type) {
            is ProfileSettingType.Toggle ->
                if (on) screenText(player, "menu.on") to LedgerScreen.GREEN else screenText(player, "menu.off") to LedgerScreen.RED
            is ProfileSettingType.Choice -> (type.options.find { it.id == setting.current }?.label ?: setting.current) to LedgerScreen.INK
            is ProfileSettingType.FreeText -> setting.current.ifBlank { screenText(player, "menu.empty") } to LedgerScreen.FADED
        }
        val (kind, hint) = when (setting.descriptor.type) {
            is ProfileSettingType.Toggle -> "menu.kind.toggle" to "menu.hint.toggle"
            is ProfileSettingType.Choice -> "menu.kind.choice" to "menu.hint.choice"
            is ProfileSettingType.FreeText -> "menu.kind.text" to "menu.hint.text"
        }
        val toggle = setting.descriptor.type is ProfileSettingType.Toggle
        return LedgerRow(
            label = setting.descriptor.label,
            value = value,
            valueColor = color,
            icon = settingItem(setting),
            badge = if (toggle) (if (on) LedgerBadge.GEM_ON else LedgerBadge.GEM_OFF) else null,
            pill = if (toggle) LedgerPill(value, if (on) PillColor.GREEN else PillColor.GREY) else null,
            tooltip = listOf(
                plain(setting.descriptor.label, GOLD),
                plain(screenText(player, kind), GREY),
                render(translations.screen(player.name, locale(player), "menu.current", "value" to value)),
                render(translations.screen(player.name, locale(player), hint)),
            ),
        )
    }

    private suspend fun handleClick(ctx: GuiClickContext, setting: ResolvedSetting) {
        val value = when (val type = setting.descriptor.type) {
            is ProfileSettingType.Toggle -> (setting.current != "true").toString()
            is ProfileSettingType.Choice -> ChoiceCycling.next(type.options, setting.current)?.id
                ?: return ctx.player.sendMessage(chat(ctx.player, "menu.no-options"))
            is ProfileSettingType.FreeText -> readFreeText(ctx, setting) ?: return
        }
        val rejection = withContext(Dispatchers.IO) {
            client.set(ctx.player.name, setting.owner, setting.descriptor.key, value)
        }
        if (rejection != null) {
            ctx.player.sendMessage(render("<#ff5a4a>$rejection"))
        }
        refreshView(ctx.player)
        ctx.gui.refresh(ctx.player)
    }

    /**
     * Opens an anvil prompt for a free-text setting; the player closing
     * the anvil (or letting it time out) simply leaves the value
     * unchanged rather than surfacing an error.
     */
    private suspend fun readFreeText(ctx: GuiClickContext, setting: ResolvedSetting): String? =
        try {
            ctx.anvilInput(setting.current, plain(setting.descriptor.label, NamedTextColor.WHITE))
        } catch (_: GuiInputCancelledException) {
            null
        } catch (_: GuiInputTimeoutException) {
            null
        }

    private suspend fun refreshView(player: Player) {
        withContext(Dispatchers.IO) { client.view(player.name) }?.let { views[player.uniqueId] = it }
        showLedger(player)
    }

    /** The row icon of a setting (the ledger puts the row tooltip on it). */
    private fun settingItem(setting: ResolvedSetting): ItemStack {
        val material = when (val type = setting.descriptor.type) {
            is ProfileSettingType.Toggle -> if (setting.current == "true") Material.LIME_DYE else Material.GRAY_DYE
            is ProfileSettingType.Choice ->
                type.options.find { it.id == setting.current }?.icon?.let(::materialOrNull) ?: Material.NAME_TAG
            is ProfileSettingType.FreeText -> Material.WRITABLE_BOOK
        }
        return ItemStack(material)
    }

    private fun materialOrNull(name: String): Material? = runCatching { Material.valueOf(name.uppercase()) }.getOrNull()

    /** A chat message (network prefix included) resolved in the player's language, rendered. */
    private fun chat(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.text(player.name, locale(player), key, *params))

    /** Prefix-free screen/GUI text resolved in the player's language, as plain text for the pixel font. */
    private fun screenText(player: Player, key: String, vararg params: Pair<String, String>): String =
        translations.screen(player.name, locale(player), key, *params)

    private fun locale(player: Player): String = player.locale().language

    private fun render(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text)).decoration(TextDecoration.ITALIC, false)

    private fun plain(text: String, color: TextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private companion object {
        /** Tooltip name and action colour (`gold` base). */
        val GOLD: TextColor = TextColor.color(0xFFCF4A)

        /** Tooltip category colour (`disabled` base). */
        val GREY: TextColor = TextColor.color(0xA89A84)

        /** Profile info lines on the note (the note holds 16 lines). */
        const val INFO_LINES = 10
    }
}
