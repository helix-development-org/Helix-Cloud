package org.helix.addons.lobby.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.display.DisplayBuilder
import de.tytoss.igui.layout.GuiArea
import de.tytoss.igui.screen.ScreenFonts
import de.tytoss.igui.screen.TextAlign
import de.tytoss.igui.screen.fit
import de.tytoss.igui.screen.glyphAt
import de.tytoss.igui.screen.textAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The navigator: a full-screen medieval board (Blockbench art, [NavigatorGlyphs]) on the shared Helix-GUIs IGui
 * instance. Opening it fetches the live joinable backends from the node and shows one coat of arms per game mode
 * (task) with emblem, name and player count; clicking anywhere on a card connects the player to that mode's
 * least-loaded backend through the proxy's BungeeCord plugin-message channel. Hovering a card shows a tooltip.
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

    private suspend fun build(gui: IGui): GuiDefinition = gui.gui("lobby-navigator") {
        rows = 6
        playerInventory = true
        landingPage = "main"
        page("main") {
            cancelAllInteractions = true
            prepare { player -> refresh(player) }
            title { ctx -> drawNavigator(ctx.player) }
            for (card in 0 until NavigatorGlyphs.CARDS) {
                val (column, row) = NavigatorGlyphs.cardCell(card)
                val area = GuiArea(column, row, 3, 5)
                // unsichtbare Items: Tooltip beim Überfahren der ganzen Karte
                area.slots().forEach { slot ->
                    item(slot) { ctx -> shown[ctx.player.uniqueId]?.getOrNull(card)?.let { tooltip(ctx.player, it) } }
                }
                onClick(area) { ctx ->
                    val group = shown[ctx.player.uniqueId]?.getOrNull(card) ?: return@onClick
                    connect(ctx.player, group.bestServiceId)
                    ctx.close()
                }
            }
        }
    }

    /** The board, a coat of arms per game mode with name and player count, the headline on the ribbon. */
    private fun DisplayBuilder.drawNavigator(player: Player) {
        val font = Key.key(NavigatorGlyphs.FONT)
        ScreenFonts.tiles(NavigatorGlyphs.BG_W, NavigatorGlyphs.BG_H).forEachIndexed { i, tile ->
            glyphAt(NavigatorGlyphs.BG_X + tile.x, font, NavigatorGlyphs.BG_TILE + i, tile.width + 1)
        }
        val groups = shown[player.uniqueId].orEmpty()
        groups.take(NavigatorGlyphs.CARDS).forEachIndexed { card, group ->
            val column = card % NavigatorGlyphs.COLUMNS
            val row = card / NavigatorGlyphs.COLUMNS
            val cx = NavigatorGlyphs.cardCenter(column)
            glyphAt(cx - NavigatorGlyphs.SHIELD_W / 2, font, NavigatorGlyphs.SHIELD + row, NavigatorGlyphs.SHIELD_W + 1)
            val emblem = NavigatorGlyphs.EMBLEMS.indexOf(group.emblem).coerceAtLeast(0)
            glyphAt(cx - NavigatorGlyphs.EMBLEM_SIZE / 2, font, NavigatorGlyphs.emblem(emblem, row), NavigatorGlyphs.EMBLEM_SIZE + 1)
            textAt(fit(group.name, NAME_WIDTH), cx, NavigatorGlyphs.nameTop(row), INK, TextAlign.CENTER)
            val count = plain.serialize(render(player, "navigator.count", "players" to group.players.toString()))
            textAt(count, cx, NavigatorGlyphs.countTop(row), if (group.players > 0) GREEN_INK else FADED_INK, TextAlign.CENTER)
        }
        if (groups.isEmpty()) {
            textAt(plain.serialize(render(player, "message.no-servers")), NavigatorGlyphs.CENTER_X, 100, INK, TextAlign.CENTER)
        }
        textAt(fit(settings.title, TITLE_WIDTH), NavigatorGlyphs.CENTER_X, NavigatorGlyphs.TITLE_TOP, GOLD, TextAlign.CENTER)
    }

    private fun tooltip(player: Player, group: ServerGroup): ItemStack = ItemStack(Material.PAPER).apply {
        editMeta { meta ->
            meta.setItemModel(NamespacedKey("helix_lobby", "empty"))
            meta.displayName(mini.deserialize("<gold>${group.name}").decoration(TextDecoration.ITALIC, false))
            meta.lore(
                listOf(
                    render(player, "menu.entry.players", "players" to group.players.toString(), "max" to group.maxPlayers.toString()),
                    render(player, "menu.entry.connect"),
                ),
            )
        }
    }

    private suspend fun refresh(player: Player) {
        val entries = withContext(Dispatchers.IO) { client.servers() }
        shown[player.uniqueId] = group(entries)
    }

    /**
     * Collapses the per-service entries into one game mode per task (configured order first), remembering the
     * least-loaded service as the connect target.
     */
    private fun group(entries: List<ServerEntry>): List<ServerGroup> {
        val configured = settings.modes.associateBy { it.task.lowercase() }
        val order = settings.modes.map { it.task.lowercase() }
        return entries.groupBy { it.task }
            .map { (task, services) ->
                val best = services.minByOrNull { it.players } ?: services.first()
                val mode = configured[task.lowercase()]
                ServerGroup(
                    name = mode?.name?.ifBlank { null } ?: task,
                    emblem = mode?.emblem ?: guessEmblem(task),
                    players = services.sumOf { it.players },
                    maxPlayers = services.sumOf { it.maxPlayers },
                    bestServiceId = best.id,
                    order = order.indexOf(task.lowercase()).let { if (it < 0) Int.MAX_VALUE else it },
                    task = task,
                )
            }
            .sortedWith(compareBy({ it.order }, { it.task }))
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
     * @property task the task name.
     * @property name display name on the coat of arms.
     * @property emblem emblem id.
     * @property players total players across the task's backends.
     * @property maxPlayers total capacity across the task's backends.
     * @property bestServiceId the least-loaded backend to connect to.
     * @property order position from the configured modes (unconfigured last).
     */
    private data class ServerGroup(
        val task: String,
        val name: String,
        val emblem: String,
        val players: Int,
        val maxPlayers: Int,
        val bestServiceId: String,
        val order: Int,
    )

    private companion object {
        /** Bukkit alias for Velocity's `bungeecord:main` plugin-message channel. */
        const val BUNGEE_CHANNEL = "BungeeCord"

        const val NAME_WIDTH = 52
        const val TITLE_WIDTH = 116

        /** Ink on parchment, faded ink, green ink, gold on the ribbon. */
        val INK: TextColor = TextColor.color(0x3B2414)
        val FADED_INK: TextColor = TextColor.color(0x8A7350)
        val GREEN_INK: TextColor = TextColor.color(0x2F6B2A)
        val GOLD: TextColor = TextColor.color(0xF2CC5A)

        /** Emblem for a task without configuration, guessed from its name. */
        fun guessEmblem(task: String): String {
            val t = task.lowercase()
            return when {
                "lobby" in t || "hub" in t -> "castle"
                "war" in t || "krieg" in t -> "banner"
                "clash" in t || "pvp" in t || "fight" in t || "duel" in t -> "swords"
                "event" in t || "vip" in t || "premium" in t -> "crown"
                else -> "compass"
            }
        }
    }
}
