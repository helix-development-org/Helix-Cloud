package org.helix.addons.lobby.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The built-in server selector, built on the shared Helix-GUIs IGui
 * instance. Opening it fetches the live joinable backends from the node,
 * groups them by task and shows one icon per task; clicking connects the
 * player to that task's least-loaded backend through the proxy's BungeeCord
 * plugin-message channel.
 *
 * The GUI is rebuilt whenever the configured rows change so an operator can
 * resize it from the dashboard.
 *
 * @property plugin the owning plugin.
 * @property client fetches the joinable backends.
 * @property translations node-backed, per-player-language texts.
 * @property scope coroutine scope IGui operations run on.
 */
class ServerMenu(
    private val plugin: JavaPlugin,
    private val client: LobbyNodeClient,
    private val translations: NodeTranslations,
    private val scope: CoroutineScope,
) {
    private val mini = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    @Volatile private var igui: IGui? = null
    @Volatile private var menu: GuiDefinition? = null
    @Volatile private var settings: ServerMenuSettings = ServerMenuSettings()

    /** Grouped entries currently shown to each viewer, index-aligned to slots. */
    private val shown = ConcurrentHashMap<UUID, List<ServerGroup>>()

    /**
     * Builds (or rebuilds) the selector for the given settings; safe to call
     * again whenever the configuration changes.
     *
     * @param current the server-menu settings to render with.
     */
    fun install(current: ServerMenuSettings) {
        settings = current
        scope.launch {
            val gui = awaitSharedIGui()
            igui = gui
            menu = build(gui)
        }
    }

    /** Drops references to the shared IGui instance; called from onDisable. */
    fun shutdown() {
        igui = null
        menu = null
        shown.clear()
    }

    /**
     * Opens the selector for a player.
     *
     * @param player the player to open it for.
     */
    fun open(player: Player) {
        val definition = menu ?: run {
            player.sendMessage(chat(player, "message.menu-starting"))
            return
        }
        scope.launch { definition.open(player) }
    }

    private suspend fun build(gui: IGui): GuiDefinition {
        val rows = settings.rows.coerceIn(1, 6)
        val titleText = plain.serialize(mini.deserialize(settings.title))
        return gui.gui("lobby-servers") {
            this.rows = rows
            landingPage = "main"
            page("main") {
                cancelAllInteractions = true
                title { _ -> centeredText(titleText, 0, color = NamedTextColor.DARK_GRAY) }
                prepare { player -> refresh(player) }
                for (index in 0 until rows * 9) {
                    item(index) { ctx -> shown[ctx.player.uniqueId]?.getOrNull(index)?.let { groupItem(ctx.player, it) } }
                    onClick(index) { ctx ->
                        val group = shown[ctx.player.uniqueId]?.getOrNull(index) ?: return@onClick
                        connect(ctx.player, group.bestServiceId)
                        ctx.close()
                    }
                }
            }
        }
    }

    private suspend fun refresh(player: Player) {
        val entries = withContext(Dispatchers.IO) { client.servers() }
        shown[player.uniqueId] = group(entries)
    }

    /**
     * Collapses the per-service entries into one row per task, remembering
     * the least-loaded service as the connect target.
     */
    private fun group(entries: List<ServerEntry>): List<ServerGroup> =
        entries.groupBy { it.task }
            .map { (task, services) ->
                val best = services.minByOrNull { it.players } ?: services.first()
                ServerGroup(
                    task = task,
                    players = services.sumOf { it.players },
                    maxPlayers = services.sumOf { it.maxPlayers },
                    bestServiceId = best.id,
                )
            }
            .sortedBy { it.task }

    private fun groupItem(player: Player, group: ServerGroup): ItemStack {
        val material = Material.matchMaterial(settings.entryMaterial) ?: Material.PAPER
        return ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(mini.deserialize("<white>${group.task}").decoration(TextDecoration.ITALIC, false))
                meta.lore(
                    listOf(
                        render(
                            player,
                            "menu.entry.players",
                            "players" to group.players.toString(),
                            "max" to group.maxPlayers.toString(),
                        ),
                        render(player, "menu.entry.connect"),
                    ),
                )
            }
        }
    }

    /**
     * Connects a player to a backend through the proxy's BungeeCord
     * plugin-message channel (Velocity enables it by default). Runs on the
     * main thread, as plugin messages must.
     */
    private fun connect(player: Player, serviceId: String) {
        if (serviceId.isBlank()) {
            player.sendMessage(chat(player, "message.no-servers"))
            return
        }
        player.sendMessage(chat(player, "message.connecting", "server" to serviceId))
        val payload = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { out ->
                out.writeUTF("Connect")
                out.writeUTF(serviceId)
            }
        }.toByteArray()
        plugin.server.scheduler.runTask(plugin, Runnable {
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, payload)
        })
    }

    private fun chat(player: Player, key: String, vararg params: Pair<String, String>) =
        render(player, key, *params)

    private fun render(player: Player, key: String, vararg params: Pair<String, String>) =
        mini.deserialize(translations.text(player.name, player.locale().language, key, *params))
            .decoration(TextDecoration.ITALIC, false)

    /**
     * One task's aggregated selector row.
     *
     * @property task the task name shown as the entry title.
     * @property players total players across the task's backends.
     * @property maxPlayers total capacity across the task's backends.
     * @property bestServiceId the least-loaded backend to connect to.
     */
    private data class ServerGroup(
        val task: String,
        val players: Int,
        val maxPlayers: Int,
        val bestServiceId: String,
    )

    private companion object {
        /** Bukkit alias for Velocity's `bungeecord:main` plugin-message channel. */
        const val BUNGEE_CHANNEL = "BungeeCord"
    }
}
