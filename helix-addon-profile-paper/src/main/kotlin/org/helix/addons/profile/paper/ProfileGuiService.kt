package org.helix.addons.profile.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.display.GuiFontConfiguration
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiInputCancelledException
import de.tytoss.igui.gui.GuiInputTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.addon.ProfileSettingType
import org.helix.api.addon.ProfileView
import org.helix.api.addon.ResolvedSetting

/**
 * The `/profilemenu` GUI, built on the vendored IGui library: one item per
 * setting the profile addon aggregates across every contributing addon.
 * Clicking a toggle flips it, clicking a choice cycles to the next
 * unlocked option, clicking free text opens an anvil prompt — every
 * change round-trips through the node's `profile.setting.set` action, so
 * this plugin holds no settings state of its own.
 *
 * @property plugin the owning plugin, for IGui installation.
 * @property client talks to the node on behalf of the menu.
 * @property scope coroutine scope IGui operations run on.
 */
class ProfileGuiService(
    private val plugin: JavaPlugin,
    private val client: ProfileNodeClient,
    private val scope: CoroutineScope,
) {
    @Volatile private var igui: IGui? = null
    @Volatile private var menu: GuiDefinition? = null
    private val views = ConcurrentHashMap<UUID, ProfileView>()

    /** Installs IGui and builds the menu definition; safe to call once on enable. */
    fun install() {
        scope.launch {
            val gui = IGui.install(plugin) {
                // Our own resource pack lives under the "helix_profile" namespace
                // (assets/helix_profile/font/*) — needed for the title's centeredText.
                fonts = GuiFontConfiguration(namespace = "helix_profile")
                // No direct database connection: texture storage proxies through the node's
                // profile.texture.* actions, like every other Paper-side component in this platform.
                database(NodeGuiTextureDatabase(client))
            }
            igui = gui
            menu = buildMenu(gui)
            plugin.logger.info("Profile menu (IGui) ready")
        }
    }

    /** Shuts IGui down asynchronously (closes open menus); called from onDisable. */
    fun shutdown() {
        val gui = igui ?: return
        scope.launch { runCatching { gui.shutdown() } }
    }

    /**
     * Opens the menu for a player.
     *
     * @param player the player to open the menu for.
     */
    fun open(player: Player) {
        val definition = menu ?: run {
            player.sendMessage(plain("Profile menu is still starting, try again in a moment.", NamedTextColor.RED))
            return
        }
        scope.launch { definition.open(player, "main") }
    }

    private suspend fun buildMenu(gui: IGui): GuiDefinition = gui.gui("profile-menu") {
        rows = 3
        landingPage = "main"

        page("main") {
            cancelAllInteractions = true
            title { _ -> centeredText("Your Profile", 0, color = NamedTextColor.WHITE) }
            prepare { player -> refreshView(player) }
            for (index in 0 until MAX_SETTINGS) {
                item(index) { ctx -> views[ctx.player.uniqueId]?.settings?.getOrNull(index)?.let(::settingItem) }
                onClick(index) { ctx ->
                    val setting = views[ctx.player.uniqueId]?.settings?.getOrNull(index) ?: return@onClick
                    handleClick(ctx, setting)
                }
            }
        }
    }

    private suspend fun handleClick(ctx: GuiClickContext, setting: ResolvedSetting) {
        val value = when (val type = setting.descriptor.type) {
            is ProfileSettingType.Toggle -> (setting.current != "true").toString()
            is ProfileSettingType.Choice -> ChoiceCycling.next(type.options, setting.current)?.id
                ?: return sendError(ctx.player, "You have no unlocked options for this setting.")
            is ProfileSettingType.FreeText -> readFreeText(ctx, setting) ?: return
        }
        val rejection = withContext(Dispatchers.IO) {
            client.set(ctx.player.name, setting.owner, setting.descriptor.key, value)
        }
        if (rejection != null) {
            sendError(ctx.player, rejection)
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
    }

    private fun sendError(player: Player, message: String) {
        player.sendMessage(plain(message, NamedTextColor.RED))
    }

    private fun settingItem(setting: ResolvedSetting): ItemStack {
        val material = when (val type = setting.descriptor.type) {
            is ProfileSettingType.Toggle -> if (setting.current == "true") Material.LIME_DYE else Material.GRAY_DYE
            is ProfileSettingType.Choice ->
                type.options.find { it.id == setting.current }?.icon?.let(::materialOrNull) ?: Material.NAME_TAG
            is ProfileSettingType.FreeText -> Material.WRITABLE_BOOK
        }
        val currentLabel = when (val type = setting.descriptor.type) {
            is ProfileSettingType.Choice -> type.options.find { it.id == setting.current }?.label ?: setting.current
            else -> setting.current
        }
        return ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(plain(setting.descriptor.label, NamedTextColor.AQUA))
                meta.lore(listOf(plain("Current: $currentLabel", NamedTextColor.GRAY)))
            }
        }
    }

    private fun materialOrNull(name: String): Material? = runCatching { Material.valueOf(name.uppercase()) }.getOrNull()

    private fun plain(text: String, color: NamedTextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private companion object {
        /** Settings shown per menu page; pagination is a later improvement. */
        const val MAX_SETTINGS = 9
    }
}
