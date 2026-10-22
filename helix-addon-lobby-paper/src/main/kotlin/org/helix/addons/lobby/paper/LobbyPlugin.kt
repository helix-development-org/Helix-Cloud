package org.helix.addons.lobby.paper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import kotlin.coroutines.CoroutineContext

/**
 * Helix-Lobby paper component.
 *
 * A backend runs as a lobby only when its `HELIX_TASK` is in the addon's
 * configured lobby tasks. While active it lays out the configured hotbar on
 * join (each item runs a command or opens the built-in server selector) and
 * enforces the toggled protection rules. Configuration arrives from the node
 * as the `lobby.config` bridge value and is re-polled, so dashboard edits
 * take effect network-wide without a restart. Every rule is a no-op on a
 * non-lobby task, so the plugin is harmless if installed everywhere.
 *
 * Players with `helix.lobby.bypass` are exempt from the protection rules so
 * operators can build and manage the lobby world.
 *
 * The lobby spawn comes from the config ([LobbyConfig.spawn], set with the team command `/setlobbyspawn`): lobby
 * services are temporary, so a spawn stored only in the world file would be lost on every restart. Joining,
 * respawning and void-falling players land there; without a configured spawn the world's own spawn is used.
 */
class LobbyPlugin : JavaPlugin(), Listener {
    private lateinit var scope: CoroutineScope
    private var client: LobbyNodeClient? = null
    private var translations: NodeTranslations? = null
    private lateinit var items: LobbyItemFactory
    private lateinit var serverMenu: ServerMenu
    private lateinit var mainWorld: World
    private val mini = MiniMessage.miniMessage()

    /** The configured spawn as a location in [mainWorld]; read by the async spawn-location event. */
    @Volatile private var spawnPoint: Location? = null

    private var task: String = "*"

    @Volatile private var config: LobbyConfig = LobbyConfig()
    @Volatile private var active: Boolean = false
    @Volatile private var initialized: Boolean = false

    /** Dispatcher running coroutines on the Bukkit main thread. */
    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) {
                server.scheduler.runTask(this@LobbyPlugin, block)
            }
        }
    }

    /** Reads the node connection, wires the components and starts the config sync. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isBlank() || controlToken.isBlank()) {
            logger.severe("HelixLobby requires HELIX_CONTROL_URL and HELIX_CONTROL_TOKEN")
            server.pluginManager.disablePlugin(this)
            return
        }
        task = System.getenv("HELIX_TASK")?.takeIf { it.isNotBlank() } ?: "*"
        mainWorld = server.worlds.first()
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)

        val client = LobbyNodeClient(controlUrl, controlToken)
        this.client = client
        val translations = NodeTranslations(controlUrl, controlToken, "helix.lobby")
        this.translations = translations
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { translations.sync() }, 1L, TRANSLATION_SYNC_TICKS)

        items = LobbyItemFactory(this)
        serverMenu = ServerMenu(this, client, translations, scope)

        server.messenger.registerOutgoingPluginChannel(this, BUNGEE_CHANNEL)
        server.pluginManager.registerEvents(this, this)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollConfig() }, 20L, CONFIG_SYNC_TICKS)

        logger.info("HelixLobby enabled for task '$task' (node: $controlUrl)")
    }

    /** Tears the components down and cancels the coroutine scope. */
    override fun onDisable() {
        if (::serverMenu.isInitialized) serverMenu.shutdown()
        if (::scope.isInitialized) scope.cancel()
        client?.close()
        client = null
        translations?.close()
        translations = null
    }

    // ------------------------------------------------------------------
    // Configuration sync
    // ------------------------------------------------------------------

    /** Fetches the latest config off-thread, then applies changes on the main thread. */
    private fun pollConfig() {
        val fetched = client?.config() ?: return
        server.scheduler.runTask(this, Runnable { applyConfig(fetched) })
    }

    private fun applyConfig(fetched: LobbyConfig) {
        if (initialized && fetched == config) return
        val serverMenuChanged = !initialized || fetched.serverMenu != config.serverMenu
        val spawnChanged = !initialized || fetched.spawn != config.spawn || active != fetched.isLobbyTask(task)
        config = fetched
        active = fetched.isLobbyTask(task)
        initialized = true
        if (serverMenuChanged) serverMenu.install(fetched.serverMenu)
        if (spawnChanged) applySpawn()
        if (active) server.onlinePlayers.forEach { applyLobby(it) }
    }

    /** Turns the configured spawn into [spawnPoint] and makes it the main world's spawn while this is a lobby. */
    private fun applySpawn() {
        val point = config.spawn?.let { Location(mainWorld, it.x, it.y, it.z, it.yaw, it.pitch) }
        spawnPoint = point
        if (active && point != null) mainWorld.setSpawnLocation(point)
    }

    /** Where lobby players spawn: the configured point, else the main world's spawn. */
    private fun lobbySpawn(): Location = spawnPoint?.clone() ?: mainWorld.spawnLocation

    /**
     * Puts a player into the lobby state: game mode, a clean inventory and
     * the configured hotbar items they are allowed to see.
     *
     * @param player the player to lay the lobby out for.
     */
    private fun applyLobby(player: Player) {
        val protection = config.protection
        if (protection.adventureMode && player.gameMode != GameMode.ADVENTURE) {
            player.gameMode = GameMode.ADVENTURE
        }
        if (protection.clearInventoryOnJoin) {
            player.inventory.clear()
        }
        config.layoutFor(task).items.forEach { item ->
            if (item.permission.isNotBlank() && !player.hasPermission(item.permission)) return@forEach
            player.inventory.setItem(item.slot.coerceIn(0, 8), items.build(item))
        }
        if (protection.preventHunger) {
            player.foodLevel = MAX_FOOD
            player.saturation = MAX_FOOD.toFloat()
        }
    }

    // ------------------------------------------------------------------
    // Item interaction
    // ------------------------------------------------------------------

    /** Lays out the lobby for a joining player. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (active) applyLobby(event.player)
    }

    /** Runs a lobby item's action when its holder clicks with it. */
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (!active || event.hand != EquipmentSlot.HAND) return
        val (action, command) = items.actionOf(event.item) ?: return
        event.isCancelled = true
        when (action) {
            ItemAction.RUN_COMMAND -> if (command.isNotBlank()) event.player.performCommand(command)
            ItemAction.OPEN_SERVER_MENU -> serverMenu.open(event.player)
        }
    }

    // ------------------------------------------------------------------
    // Protection
    // ------------------------------------------------------------------

    /** Cancels block breaking for lobby players without bypass. */
    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (guarded(event.player, config.protection.preventBlockBreak)) event.isCancelled = true
    }

    /** Cancels block placing for lobby players without bypass. */
    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (guarded(event.player, config.protection.preventBlockPlace)) event.isCancelled = true
    }

    /** Cancels all damage to lobby players without bypass. */
    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (guarded(player, config.protection.preventDamage)) event.isCancelled = true
    }

    /** Keeps a lobby player's food bar full. */
    @EventHandler(ignoreCancelled = true)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (active && config.protection.preventHunger) {
            event.foodLevel = MAX_FOOD
        }
    }

    /** Cancels item drops for lobby players without bypass. */
    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (guarded(event.player, config.protection.preventItemDrop)) event.isCancelled = true
    }

    /** Locks a lobby player's inventory (no item moving) without bypass. */
    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (guarded(player, config.protection.preventItemMove)) event.isCancelled = true
    }

    /** Teleports a lobby player back to the lobby spawn when they fall into the void. */
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!active || !config.protection.voidTeleport) return
        val to = event.to
        if (to.y >= config.protection.voidTeleportY) return
        event.player.teleport(lobbySpawn())
    }

    /** Joining players appear at the configured lobby spawn (runs off the main thread, before the join). */
    @EventHandler
    fun onSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        if (!active) return
        spawnPoint?.let { event.spawnLocation = it.clone() }
    }

    /** Respawning lobby players come back at the lobby spawn. */
    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        if (active) event.respawnLocation = lobbySpawn()
    }

    // ------------------------------------------------------------------
    // /setlobbyspawn
    // ------------------------------------------------------------------

    /** `/setlobbyspawn`: stores the sender's position as the lobby spawn on the node (all lobbies, restart-proof). */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(plainText("spawn.players-only"))
            return true
        }
        when {
            !active -> message(player, "spawn.not-lobby")
            player.world != mainWorld -> message(player, "spawn.wrong-world")
            else -> saveSpawn(player)
        }
        return true
    }

    private fun saveSpawn(player: Player) {
        val location = player.location
        val spawn = LobbySpawn(location.x, location.y, location.z, location.yaw, location.pitch)
        val name = player.name
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val error = client?.saveSpawn(name, spawn) ?: "unreachable"
            server.scheduler.runTask(this, Runnable {
                if (error == null) {
                    config = config.copy(spawn = spawn)
                    applySpawn()
                }
                if (!player.isOnline) return@Runnable
                if (error == null) {
                    message(player, "spawn.saved", "x" to fmt(spawn.x), "y" to fmt(spawn.y), "z" to fmt(spawn.z))
                } else {
                    message(player, "spawn.failed", "reason" to error)
                }
            })
        })
    }

    private fun message(player: Player, key: String, vararg params: Pair<String, String>) {
        val text = translations?.text(player.name, player.locale().language, key, *params) ?: key
        player.sendMessage(mini.deserialize(text))
    }

    private fun plainText(key: String): String = translations?.text("", null, key) ?: key

    private fun fmt(value: Double) = String.format(java.util.Locale.ROOT, "%.1f", value)

    /**
     * Whether a protection rule should fire for a player: the server is a
     * lobby, the rule is enabled and the player has no bypass.
     *
     * @param player the affected player.
     * @param enabled whether the specific rule is turned on.
     * @return `true` when the event should be cancelled.
     */
    private fun guarded(player: Player, enabled: Boolean): Boolean =
        active && enabled && !player.hasPermission(BYPASS_PERMISSION)

    private companion object {
        /** How often the config re-syncs from the node (20 ticks = 1 second). */
        const val CONFIG_SYNC_TICKS = 100L

        /** How often the translation snapshot re-syncs from the node. */
        const val TRANSLATION_SYNC_TICKS = 100L

        /** A full food bar. */
        const val MAX_FOOD = 20

        /** Permission exempting a player from lobby protection. */
        const val BYPASS_PERMISSION = "helix.lobby.bypass"

        /** Bukkit alias for Velocity's `bungeecord:main` plugin-message channel. */
        const val BUNGEE_CHANNEL = "BungeeCord"
    }
}
