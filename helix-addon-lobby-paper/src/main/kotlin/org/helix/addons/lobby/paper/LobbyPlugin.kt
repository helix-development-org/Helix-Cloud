package org.helix.addons.lobby.paper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.GameMode
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
 */
class LobbyPlugin : JavaPlugin(), Listener {
    private lateinit var scope: CoroutineScope
    private var client: LobbyNodeClient? = null
    private var translations: NodeTranslations? = null
    private lateinit var items: LobbyItemFactory
    private lateinit var serverMenu: ServerMenu

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
        config = fetched
        active = fetched.isLobbyTask(task)
        initialized = true
        if (serverMenuChanged) serverMenu.install(fetched.serverMenu)
        if (active) server.onlinePlayers.forEach { applyLobby(it) }
    }

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
        val phone = config.phone
        if (phone.enabled) {
            // Phone mode: a single item that opens the phone, which hosts everything.
            val item = LobbyItem(
                slot = phone.slot,
                material = phone.material,
                name = phone.name,
                action = ItemAction.RUN_COMMAND,
                command = "phone",
            )
            player.inventory.setItem(phone.slot.coerceIn(0, 8), items.build(item))
        } else {
            config.layoutFor(task).items.forEach { item ->
                if (item.permission.isNotBlank() && !player.hasPermission(item.permission)) return@forEach
                player.inventory.setItem(item.slot.coerceIn(0, 8), items.build(item))
            }
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

    /** Teleports a lobby player back to spawn when they fall into the void. */
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!active || !config.protection.voidTeleport) return
        val to = event.to
        if (to.y >= config.protection.voidTeleportY) return
        val spawn = event.player.respawnLocation ?: event.player.world.spawnLocation
        event.player.teleport(spawn)
    }

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
