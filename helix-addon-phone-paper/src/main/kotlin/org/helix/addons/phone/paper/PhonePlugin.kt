package org.helix.addons.phone.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.display.DisplayBuilder
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiDefinition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Helix-Phone paper component.
 *
 * Draws the phone as a single 176×222 title glyph that overlays the whole
 * inventory (the borrowed player inventory is cleared so the case shows
 * through), lays the player's apps out as icon glyphs on a 4×5 home grid and
 * routes taps: a command app runs its command, a native app opens a built-in
 * screen (navigator = server selector; messages = the BetterMSGs UI). The
 * app list and their resolved icon glyphs come from the node per player, so
 * a player only ever sees apps whose icons are in the pack they loaded.
 */
class PhonePlugin : JavaPlugin(), Listener {
    private val mini = MiniMessage.miniMessage()
    private lateinit var scope: CoroutineScope
    private lateinit var takeover: InventoryTakeover
    private var client: PhoneNodeClient? = null
    private var translations: NodeTranslations? = null

    @Volatile private var home: GuiDefinition? = null

    private val apps = ConcurrentHashMap<UUID, List<AppView>>()
    private val servers = ConcurrentHashMap<UUID, List<ServerEntry>>()

    /** Dispatcher running coroutines on the Bukkit main thread. */
    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) {
                server.scheduler.runTask(this@PhonePlugin, block)
            }
        }
    }

    /** Reads the node connection, wires the transport and builds the phone GUI. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isBlank() || controlToken.isBlank()) {
            logger.severe("HelixPhone requires HELIX_CONTROL_URL and HELIX_CONTROL_TOKEN")
            server.pluginManager.disablePlugin(this)
            return
        }
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        takeover = InventoryTakeover(dataFolder.toPath().resolve("inventories"))
        val client = PhoneNodeClient(controlUrl, controlToken)
        this.client = client
        val translations = NodeTranslations(controlUrl, controlToken, "helix.phone")
        this.translations = translations
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { translations.sync() }, 1L, TRANSLATION_SYNC_TICKS)

        server.messenger.registerOutgoingPluginChannel(this, BUNGEE_CHANNEL)
        server.pluginManager.registerEvents(this, this)

        scope.launch { home = buildPhone(awaitSharedIGui()) }
        logger.info("HelixPhone enabled (node: $controlUrl)")
    }

    /** Restores borrowed inventories and tears the transport down. */
    override fun onDisable() {
        if (::takeover.isInitialized) {
            takeover.restoreAll { Bukkit.getPlayer(it) }
        }
        if (::scope.isInitialized) {
            scope.cancel()
        }
        client?.close()
        client = null
        translations?.close()
        translations = null
    }

    /** Opens the phone home for the executing player. */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only players can open the phone.")
            return true
        }
        val definition = home ?: run {
            player.sendMessage(chat(player, "message.starting"))
            return true
        }
        scope.launch { definition.open(player, "home") }
        return true
    }

    /** Restores a crash snapshot on join. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (::takeover.isInitialized) {
            takeover.restoreCrashed(event.player)
        }
    }

    /** Restores the borrowed inventory if the player quits with the phone open. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (::takeover.isInitialized) {
            takeover.restore(event.player)
        }
        apps.remove(event.player.uniqueId)
        servers.remove(event.player.uniqueId)
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    private suspend fun buildPhone(gui: IGui): GuiDefinition = gui.gui("phone.home") {
        rows = 6
        landingPage = "home"
        onClose { context -> takeover.restore(context.player) }

        page("home") {
            cancelAllInteractions = true
            onOpen { player -> takeover.begin(player) }
            prepare { player -> apps[player.uniqueId] = loadApps(player) }
            title { ctx -> renderHome(ctx.player) }
            for (index in 0 until PhoneLayout.CAPACITY) {
                onClick(PhoneLayout.slotForIndex(index)) { ctx -> tapApp(ctx, index) }
            }
            onClick(PhoneLayout.CLOSE_SLOT) { ctx -> ctx.close() }
        }

        page("navigator") {
            cancelAllInteractions = true
            prepare { player -> servers[player.uniqueId] = loadServers() }
            title { ctx -> renderNavigator(ctx.player) }
            for (index in 0 until PhoneLayout.CAPACITY) {
                item(PhoneLayout.slotForIndex(index)) { ctx ->
                    servers[ctx.player.uniqueId]?.getOrNull(index)?.let(::serverItem)
                }
                onClick(PhoneLayout.slotForIndex(index)) { ctx ->
                    servers[ctx.player.uniqueId]?.getOrNull(index)?.let { connect(ctx.player, it.id) }
                }
            }
            onClick(PhoneLayout.CLOSE_SLOT) { ctx -> ctx.openPage("home") }
        }
    }

    private fun DisplayBuilder.renderHome(player: Player) {
        moveTo(0)
        text(PhoneLayout.CASE_CHAR, 0, NamedTextColor.WHITE, PhoneLayout.CASE_FONT)
        toStart()
        val visible = apps[player.uniqueId] ?: return
        visible.take(PhoneLayout.CAPACITY).forEachIndexed { index, app ->
            if (app.iconChar.isEmpty()) return@forEachIndexed
            moveTo(PhoneLayout.iconX(index))
            text(app.iconChar, 0, NamedTextColor.WHITE, PhoneLayout.iconFont(app.iconFont, index))
            toStart()
        }
    }

    private fun DisplayBuilder.renderNavigator(@Suppress("UNUSED_PARAMETER") player: Player) {
        moveTo(0)
        text(PhoneLayout.CASE_CHAR, 0, NamedTextColor.WHITE, PhoneLayout.CASE_FONT)
        toStart()
    }

    private suspend fun loadApps(player: Player): List<AppView> =
        withContext(Dispatchers.IO) { client?.apps(player.name).orEmpty() }

    private suspend fun loadServers(): List<ServerEntry> =
        withContext(Dispatchers.IO) { client?.servers().orEmpty() }

    private suspend fun tapApp(ctx: GuiClickContext, index: Int) {
        val app = apps[ctx.player.uniqueId]?.getOrNull(index) ?: return
        when (app.kind) {
            AppKind.COMMAND -> {
                ctx.close()
                runCommand(ctx.player, app.command)
            }
            AppKind.NATIVE -> when (app.screen) {
                "navigator" -> ctx.openPage("navigator")
                "messages" -> {
                    ctx.close()
                    runCommand(ctx.player, "msg")
                }
                else -> {
                    ctx.close()
                    if (app.command.isNotBlank()) {
                        runCommand(ctx.player, app.command)
                    } else {
                        ctx.player.sendMessage(chat(ctx.player, "message.no-app"))
                    }
                }
            }
        }
    }

    private fun runCommand(player: Player, command: String) {
        val trimmed = command.removePrefix("/").trim()
        if (trimmed.isNotBlank()) {
            player.performCommand(trimmed)
        }
    }

    private fun serverItem(entry: ServerEntry): ItemStack = ItemStack(Material.PAPER).apply {
        editMeta { meta ->
            meta.displayName(mini.deserialize("<white>${entry.task}").decoration(TextDecoration.ITALIC, false))
            meta.lore(
                listOf(
                    mini.deserialize("<gray>${entry.id} · ${entry.players}/${entry.maxPlayers}")
                        .decoration(TextDecoration.ITALIC, false),
                ),
            )
        }
    }

    private fun connect(player: Player, serviceId: String) {
        if (serviceId.isBlank()) {
            return
        }
        val payload = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { out ->
                out.writeUTF("Connect")
                out.writeUTF(serviceId)
            }
        }.toByteArray()
        server.scheduler.runTask(this, Runnable { player.sendPluginMessage(this, BUNGEE_CHANNEL, payload) })
    }

    private fun chat(player: Player, key: String) =
        mini.deserialize(translations?.text(player.name, player.locale().language, key).orEmpty())
            .decoration(TextDecoration.ITALIC, false)

    private companion object {
        /** How often the translation snapshot re-syncs from the node. */
        const val TRANSLATION_SYNC_TICKS = 100L

        /** Bukkit alias for Velocity's `bungeecord:main` plugin-message channel. */
        const val BUNGEE_CHANNEL = "BungeeCord"
    }
}
