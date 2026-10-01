package org.helix.cinematic

import de.tytoss.inpc.INpc
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Entry point of the cinematic library: plays [Scene]s for players.
 *
 * One plugin installs it ([install]; on Helix: HelixCinematic, which also ships the fade font in its resource
 * pack) and shares it through Bukkit's ServicesManager; every other plugin gets that instance with [shared]
 * (declare `depend: [HelixCinematic]`).
 *
 * ```
 * Cinematics.shared().play(player, scene("intro") { shot(160) { … } }) {
 *     skipOnInput = false
 *     onFinish { reason -> player.sendMessage("done: $reason") }
 * }
 * ```
 *
 * A player watches at most one playback; starting another ends the current one ([FinishReason.STOPPED]).
 */
class Cinematics private constructor(private val plugin: JavaPlugin) : Listener {
    private val playbacks = HashMap<UUID, Playback>()
    private var task: BukkitTask? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleaner = ScreenCleaner()
    private val services = Services(INpc.install(plugin) {}, scope, cleaner)

    /**
     * Plays [scene] for [viewer] (in the viewer's world).
     *
     * @param viewer who watches.
     * @param scene what to play.
     * @param options playback options.
     * @return the running playback.
     */
    fun play(viewer: Player, scene: Scene, options: PlaybackOptions.() -> Unit = {}): Playback {
        playbacks[viewer.uniqueId]?.stop(FinishReason.STOPPED)
        val playback = Playback(plugin, services, viewer, scene, PlaybackOptions().apply(options)) { ended ->
            if (playbacks[ended.viewer.uniqueId] === ended) playbacks.remove(ended.viewer.uniqueId)
        }
        playbacks[viewer.uniqueId] = playback
        playback.start()
        return playback
    }

    /**
     * @param viewer a player.
     * @return the playback the player watches, if any.
     */
    fun playing(viewer: Player): Playback? = playbacks[viewer.uniqueId]?.takeIf { it.running }

    /**
     * Ends the playback of [viewer], if any.
     *
     * @param viewer a player.
     */
    fun stop(viewer: Player) {
        playbacks[viewer.uniqueId]?.stop(FinishReason.STOPPED)
    }

    private fun tick() {
        playbacks.values.toList().forEach { it.tick() }
    }

    private fun shutdown() {
        task?.cancel()
        playbacks.values.toList().forEach { it.stop(FinishReason.STOPPED) }
        playbacks.clear()
        HandlerList.unregisterAll(this)
        cleaner.stop()
        runCatching { runBlocking { withTimeoutOrNull(2000) { services.npcs.shutdown() } } }
        scope.cancel()
    }

    private fun input(player: Player) {
        val playback = playbacks[player.uniqueId] ?: return
        if (playback.options.skipOnInput) plugin.server.scheduler.runTask(plugin, Runnable { playback.stop(FinishReason.SKIPPED) })
    }

    /** Walking or looking around. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.x != to.x || from.y != to.y || from.z != to.z || from.yaw != to.yaw || from.pitch != to.pitch) input(event.player)
    }

    /** Chat. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChat(event: AsyncChatEvent) = input(event.player)

    /** Commands. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onCommand(event: PlayerCommandPreprocessEvent) = input(event.player)

    /** Clicks. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInteract(event: PlayerInteractEvent) = input(event.player)

    /** Hotbar scrolling. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onHeld(event: PlayerItemHeldEvent) = input(event.player)

    /** Sneaking. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onSneak(event: PlayerToggleSneakEvent) = input(event.player)

    /** Hand swap. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onSwap(event: PlayerSwapHandItemsEvent) = input(event.player)

    /** Dropping items. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDrop(event: PlayerDropItemEvent) = input(event.player)

    /** Inventories. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventory(event: InventoryOpenEvent) {
        (event.player as? Player)?.let(::input)
    }

    /** Damage. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        (event.entity as? Player)?.let(::input)
    }

    /** Teleports leave the camera behind – always ends the playback. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        playbacks[event.player.uniqueId]?.let { pb -> plugin.server.scheduler.runTask(plugin, Runnable { pb.stop(FinishReason.QUIT) }) }
    }

    /** World changes end the playback. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorld(event: PlayerChangedWorldEvent) {
        playbacks[event.player.uniqueId]?.stop(FinishReason.QUIT)
    }

    /** Leaving ends the playback. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        playbacks[event.player.uniqueId]?.stop(FinishReason.QUIT)
    }

    /** Installation and the shared instance. */
    companion object {
        /**
         * Installs the library for [plugin] (listeners, tick task) and shares it via the ServicesManager.
         * Call once, from the owning plugin's `onEnable`.
         *
         * @param plugin the owning plugin.
         * @return the instance.
         */
        fun install(plugin: JavaPlugin): Cinematics {
            val cinematics = Cinematics(plugin)
            plugin.server.pluginManager.registerEvents(cinematics, plugin)
            cinematics.cleaner.start()
            cinematics.task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { cinematics.tick() }, 1L, 1L)
            Bukkit.getServicesManager().register(Cinematics::class.java, cinematics, plugin, ServicePriority.Normal)
            return cinematics
        }

        /**
         * Ends every playback and unregisters the instance; call from the owning plugin's `onDisable`.
         *
         * @param cinematics the installed instance.
         */
        fun uninstall(cinematics: Cinematics) {
            cinematics.shutdown()
            Bukkit.getServicesManager().unregister(Cinematics::class.java, cinematics)
        }

        /**
         * @return the shared instance installed by the owning plugin.
         * @throws IllegalStateException if none is installed (missing `depend: [HelixCinematic]`?).
         */
        fun shared(): Cinematics = Bukkit.getServicesManager().load(Cinematics::class.java)
            ?: error("No Cinematics instance installed - add `depend: [HelixCinematic]` to plugin.yml")
    }
}
