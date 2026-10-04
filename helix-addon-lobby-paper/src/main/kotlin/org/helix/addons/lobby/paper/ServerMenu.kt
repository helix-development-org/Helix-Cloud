package org.helix.addons.lobby.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.awaitSharedIGui
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.display.DisplayBuilder
import de.tytoss.igui.layout.GuiArea
import de.tytoss.igui.layout.GuiGrid
import de.tytoss.igui.screen.ScreenFonts
import de.tytoss.igui.screen.VillagerHeads
import de.tytoss.igui.sound.NetworkSounds
import de.tytoss.igui.sound.SoundPlayer
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
 * The navigator "Karte des Reichs" (design concept §14): a hand-drawn map (Blockbench art, [NavigatorGlyphs]) on the
 * shared IGui instance. Opening it fetches the live joinable backends from the node and puts every game mode (task)
 * on a place of the map – animated emblem on a plaque, name banner, the responsible villager's head beside it.
 * Clicking a place connects the player to that mode's least-loaded backend through the proxy's BungeeCord
 * plugin-message channel; full places show a red banner, places under maintenance a grey emblem and a fence.
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

    /** The places currently shown to each viewer. */
    private val spots = ConcurrentHashMap<UUID, List<Spot>>()

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
        spots.clear()
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
            title { ctx -> drawMap(ctx.player) }
            val grid = GuiGrid(6, true)
            NavigatorGlyphs.PLACES.forEachIndexed { index, place ->
                val area = GuiArea(place.column, place.row, 2, 2)
                val emblemSlot = grid.slot(place.column, place.row)
                val headSlot = grid.slot(place.headColumn, place.headRow)
                // the block's top-left cell carries the animated emblem, the others invisible tooltip items
                area.slots().forEach { slot ->
                    item(slot) { ctx ->
                        val spot = spotAt(ctx.player, index) ?: return@item null
                        if (slot == emblemSlot) emblemItem(ctx.player, spot) else tooltip(ctx.player, spot, NamespacedKey(NavigatorGlyphs.SPRITE_NAMESPACE, "empty"))
                    }
                }
                item(headSlot) { ctx ->
                    val spot = spotAt(ctx.player, index) ?: return@item null
                    VillagerHeads.item(spot.villager, tooltipLines(ctx.player, spot))
                }
                val click: suspend (de.tytoss.igui.gui.GuiClickContext) -> Unit = { ctx ->
                    val spot = spotAt(ctx.player, index)
                    val group = spot?.group
                    if (group != null && spot.state != PlaceState.MAINTENANCE) {
                        SoundPlayer.play(ctx.player, NetworkSounds.UI_PAGE)
                        connect(ctx.player, group.bestServiceId)
                        ctx.close()
                    } else {
                        SoundPlayer.play(ctx.player, NetworkSounds.UI_ERROR)
                    }
                }
                onClick(area) { ctx -> click(ctx) }
                onClick(headSlot) { ctx -> click(ctx) }
            }
        }
    }

    /** The map, a plaque with banner per occupied place (emblems are items), the headline on the ribbon. */
    private fun DisplayBuilder.drawMap(player: Player) {
        val font = Key.key(NavigatorGlyphs.FONT)
        ScreenFonts.tiles(NavigatorGlyphs.BG_W, NavigatorGlyphs.BG_H).forEachIndexed { i, tile ->
            glyphAt(NavigatorGlyphs.BG_X + tile.x, font, NavigatorGlyphs.BG_TILE + i, tile.width + 1)
        }
        spots[player.uniqueId].orEmpty().forEach { spot ->
            val place = spot.place
            val i = NavigatorGlyphs.PLACES.indexOf(place)
            if (spot.group != null) {
                glyphAt(place.centerX - NavigatorGlyphs.PLAQUE_SIZE / 2, font, NavigatorGlyphs.PLAQUE + i, NavigatorGlyphs.PLAQUE_SIZE + 1)
            }
            if (spot.state == PlaceState.MAINTENANCE && spot.group != null) {
                glyphAt(place.centerX + 10, font, NavigatorGlyphs.FENCE + i, NavigatorGlyphs.FENCE_SIZE + 1)
            }
            glyphAt(place.centerX - NavigatorGlyphs.BAND_W / 2, font, NavigatorGlyphs.BAND + i * 3 + spot.state.ordinal, NavigatorGlyphs.BAND_W + 1)
            val color = when (spot.state) {
                PlaceState.NORMAL -> INK
                PlaceState.FULL -> WHITE
                PlaceState.MAINTENANCE -> INK
            }
            textAt(fit(spot.name, NAME_WIDTH), place.centerX + 1, place.bandTop + 2, color, TextAlign.CENTER, if (spot.state == PlaceState.FULL) INK else null)
        }
        textAt(fit(settings.title, TITLE_WIDTH), NavigatorGlyphs.CENTER_X, NavigatorGlyphs.TITLE_TOP, GOLD, TextAlign.CENTER, INK)
    }

    private fun spotAt(player: Player, place: Int): Spot? =
        spots[player.uniqueId]?.firstOrNull { it.place == NavigatorGlyphs.PLACES[place] }

    private fun emblemItem(player: Player, spot: Spot): ItemStack? {
        val group = spot.group ?: return tooltip(player, spot, NamespacedKey(NavigatorGlyphs.SPRITE_NAMESPACE, "empty"))
        val emblem = NavigatorGlyphs.EMBLEMS.indexOf(group.emblem).coerceAtLeast(0)
        val sprite = NavigatorGlyphs.emblemSprite(emblem, grey = spot.state == PlaceState.MAINTENANCE)
        return tooltip(player, spot, NamespacedKey(NavigatorGlyphs.SPRITE_NAMESPACE, sprite.model))
    }

    private fun tooltip(player: Player, spot: Spot, model: NamespacedKey): ItemStack = ItemStack(Material.PAPER).apply {
        val lines = tooltipLines(player, spot)
        editMeta { meta ->
            meta.setItemModel(model)
            meta.displayName(lines.first())
            meta.lore(lines.drop(1))
        }
    }

    /** Tooltip per design concept §14: name in gold, player count, one line of lore, the action in gold. */
    private fun tooltipLines(player: Player, spot: Spot): List<net.kyori.adventure.text.Component> {
        val group = spot.group
        val lines = mutableListOf(mini.deserialize("<#ffcf4a>${spot.name}").decoration(TextDecoration.ITALIC, false))
        if (group != null) {
            lines += render(player, "navigator.tooltip.players", "players" to group.players.toString(), "max" to group.maxPlayers.toString())
        }
        val lore = spot.lore.ifBlank { translations.text(player.name, player.locale().language, "navigator.lore.${spot.place.id}") }
        if (lore.isNotBlank() && !lore.startsWith("navigator.")) {
            lines += mini.deserialize("<#d8c08e>$lore").decoration(TextDecoration.ITALIC, false)
        }
        lines += when {
            group == null -> render(player, "navigator.tooltip.soon")
            spot.state == PlaceState.MAINTENANCE -> render(player, "navigator.tooltip.maintenance")
            spot.state == PlaceState.FULL -> render(player, "navigator.tooltip.full")
            else -> render(player, "navigator.tooltip.travel")
        }
        return lines
    }

    /**
     * Puts every game mode on a place of the map: its configured place, else a place guessed from the task name,
     * else the next free one. The fog place shows "coming soon" while it is free.
     */
    private fun layout(groups: List<ServerGroup>): List<Spot> {
        val taken = mutableMapOf<NavigatorGlyphs.Place, ServerGroup>()
        val places = NavigatorGlyphs.PLACES.associateBy { it.id }
        groups.forEach { group ->
            val wanted = listOfNotNull(places[group.place], places[guessPlace(group.task)])
            val place = wanted.firstOrNull { it !in taken } ?: AUTO_ORDER.mapNotNull { places[it] }.firstOrNull { it !in taken }
            if (place != null) taken[place] = group
        }
        val out = taken.map { (place, group) ->
            val state = when {
                group.maintenance || group.bestServiceId.isBlank() -> PlaceState.MAINTENANCE
                group.maxPlayers > 0 && group.players >= group.maxPlayers -> PlaceState.FULL
                else -> PlaceState.NORMAL
            }
            Spot(place, group, state, group.name, group.villager.ifBlank { place.villager }, group.lore)
        }.toMutableList()
        val fog = places.getValue(NavigatorGlyphs.FOG_PLACE)
        if (fog !in taken) out += Spot(fog, null, PlaceState.MAINTENANCE, fog.name, fog.villager, "")
        return out
    }

    private suspend fun refresh(player: Player) {
        val entries = withContext(Dispatchers.IO) { client.servers() }
        spots[player.uniqueId] = layout(group(entries))
    }

    /**
     * Collapses the per-service entries into one game mode per task (configured order first), remembering the
     * least-loaded service as the connect target.
     */
    private fun group(entries: List<ServerEntry>): List<ServerGroup> {
        val configured = settings.modes.associateBy { it.task.lowercase() }
        val order = settings.modes.map { it.task.lowercase() }
        val running = entries.groupBy { it.task }
        val tasks = (settings.modes.map { it.task } + running.keys).distinctBy { it.lowercase() }
        return tasks.map { task ->
            val services = running.entries.firstOrNull { it.key.equals(task, ignoreCase = true) }?.value.orEmpty()
            val best = services.minByOrNull { it.players }
            val mode = configured[task.lowercase()]
            ServerGroup(
                name = mode?.name?.ifBlank { null } ?: task,
                emblem = mode?.emblem ?: guessEmblem(task),
                players = services.sumOf { it.players },
                maxPlayers = services.sumOf { it.maxPlayers },
                bestServiceId = best?.id.orEmpty(),
                order = order.indexOf(task.lowercase()).let { if (it < 0) Int.MAX_VALUE else it },
                task = task,
                place = mode?.place.orEmpty(),
                villager = mode?.villager.orEmpty(),
                lore = mode?.lore.orEmpty(),
                maintenance = mode?.maintenance == true,
            )
        }.sortedWith(compareBy({ it.order }, { it.task }))
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
     * @property place configured place id (empty: guessed).
     * @property villager configured villager id (empty: the place's).
     * @property lore configured tooltip lore (empty: the place's default text).
     * @property maintenance whether the mode is marked as under maintenance.
     */
    private data class ServerGroup(
        val task: String,
        val name: String,
        val emblem: String,
        val players: Int,
        val maxPlayers: Int,
        val bestServiceId: String,
        val order: Int,
        val place: String = "",
        val villager: String = "",
        val lore: String = "",
        val maintenance: Boolean = false,
    )

    /** How a place is shown (banner glyph order). */
    private enum class PlaceState { NORMAL, FULL, MAINTENANCE }

    /**
     * A place on the map with what stands there.
     *
     * @property place the place.
     * @property group the game mode there, or null (fog place without a mode).
     * @property state shown state.
     * @property name shown name.
     * @property villager villager id of the head beside it.
     * @property lore configured lore line (empty: default).
     */
    private data class Spot(
        val place: NavigatorGlyphs.Place,
        val group: ServerGroup?,
        val state: PlaceState,
        val name: String,
        val villager: String,
        val lore: String,
    )

    private companion object {
        /** Bukkit alias for Velocity's `bungeecord:main` plugin-message channel. */
        const val BUNGEE_CHANNEL = "BungeeCord"

        const val NAME_WIDTH = 60
        const val TITLE_WIDTH = 132

        /** Ink on parchment, white on the red banner, gold on the ribbon (design tokens). */
        val INK: TextColor = TextColor.color(0x2A160A)
        val WHITE: TextColor = TextColor.color(0xFFFFFF)
        val GOLD: TextColor = TextColor.color(0xFFCF4A)

        /** Places filled in this order when a mode names none and its task gives no hint. */
        val AUTO_ORDER = listOf("kesseldorf", "rosenarena", "grenzlande", "reserve", "ankerplatz", "nebelland")

        /** Place for a task without configuration, guessed from its name. */
        fun guessPlace(task: String): String {
            val t = task.lowercase()
            return when {
                "lobby" in t || "hub" in t -> "ankerplatz"
                "village" in t || "dorf" in t || "clash" in t -> "kesseldorf"
                "1v1" in t || "duel" in t || "arena" in t -> "rosenarena"
                "war" in t || "krieg" in t -> "grenzlande"
                else -> ""
            }
        }

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
