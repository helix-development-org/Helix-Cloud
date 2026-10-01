package org.helix.addons.cinematic.paper

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.GameMode
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.helix.cinematic.Cinematics
import org.helix.cinematic.Scene
import org.helix.cinematic.Shots
import org.helix.cinematic.Target
import org.helix.cinematic.Transition
import org.helix.cinematic.scene
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * HelixCinematic: installs and shares the cinematic library ([Cinematics], `helix-library-cinematic`) for the
 * whole server – other plugins use it with `depend: [HelixCinematic]` and [Cinematics.shared] – and runs its first
 * user, the **cinematic AFK camera** of the `helix.cinematic` addon: after [CinematicSettings.afkSeconds]
 * without input on a task listed in the settings, an endless scene of drone shots ([Shots.drone]) films the
 * player; the first shot shows their name, others see an AFK tag above them. Any input ends it (the library's
 * skip-on-input).
 *
 * Settings and profile opt-outs come from the node's bridge values (`cinematic.config`, `cinematic.off.<player>`).
 */
class CinematicPlugin : JavaPlugin(), Listener {
    private lateinit var cinematics: Cinematics
    private var client: BridgeValuesClient? = null
    private var fetchTask: BukkitTask? = null
    private var tickTask: BukkitTask? = null

    @Volatile private var settings = CinematicSettings()

    @Volatile private var optedOut: Set<String> = emptySet()
    private val task = System.getenv("HELIX_TASK").orEmpty()
    private val lastInput = ConcurrentHashMap<UUID, Long>()
    private val afkTags = HashMap<UUID, TextDisplay>()

    /** Installs the library, connects to the node and starts the AFK watcher. */
    override fun onEnable() {
        cinematics = Cinematics.install(this)
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val token = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        val serviceId = System.getenv("HELIX_SERVICE_ID").orEmpty()
        server.pluginManager.registerEvents(this, this)
        if (controlUrl.isBlank() || token.isBlank() || serviceId.isBlank()) {
            logger.warning("No Helix node configured – the library is available, the AFK camera stays off")
            return
        }
        val client = BridgeValuesClient(controlUrl, token, serviceId).also { this.client = it }
        fetchTask = server.scheduler.runTaskTimerAsynchronously(this, Runnable {
            val values = client.fetch() ?: return@Runnable
            settings = CinematicSettings.from(values)
            optedOut = CinematicSettings.optedOut(values)
        }, 0L, FETCH_PERIOD_TICKS)
        tickTask = server.scheduler.runTaskTimer(this, Runnable { tick() }, 20L, 5L)
        server.onlinePlayers.forEach { input(it) }
        logger.info("HelixCinematic enabled (task $task)")
    }

    /** Stops everything. */
    override fun onDisable() {
        fetchTask?.cancel()
        tickTask?.cancel()
        afkTags.values.forEach { it.remove() }
        afkTags.clear()
        if (::cinematics.isInitialized) Cinematics.uninstall(cinematics)
        client?.close()
        client = null
    }

    private fun tick() {
        val current = settings
        val active = current.activeOn(task)
        val now = System.currentTimeMillis()
        server.onlinePlayers.forEach { player ->
            val playback = cinematics.playing(player)
            if (playback != null) {
                if (playback.scene.name == AFK_SCENE && (!active || player.name.lowercase() in optedOut)) playback.stop()
                afkTags[player.uniqueId]?.teleport(player.location.add(0.0, TAG_HEIGHT, 0.0).apply { yaw = 0f; pitch = 0f })
                return@forEach
            }
            if (!active) return@forEach
            val idleMs = now - (lastInput[player.uniqueId] ?: now.also { lastInput[player.uniqueId] = it })
            if (idleMs >= current.afkSeconds * 1000L && canStart(player)) startAfk(player, current)
        }
    }

    private fun canStart(player: Player): Boolean =
        player.name.lowercase() !in optedOut &&
            player.gameMode != GameMode.SPECTATOR &&
            player.isOnGround && !player.isFlying && !player.isGliding && !player.isInsideVehicle && !player.isSleeping &&
            player.openInventory.topInventory.type == InventoryType.CRAFTING

    private fun startAfk(player: Player, current: CinematicSettings) {
        cinematics.play(player, afkScene(player, current)) {
            onFinish {
                afkTags.remove(player.uniqueId)?.remove()
                lastInput[player.uniqueId] = System.currentTimeMillis()
            }
        }
        if (current.afkTag.isNotBlank()) {
            afkTags[player.uniqueId] = player.world.spawn(player.location.add(0.0, TAG_HEIGHT, 0.0), TextDisplay::class.java) { tag ->
                tag.isPersistent = false
                tag.billboard = Display.Billboard.CENTER
                tag.text(MiniMessage.miniMessage().deserialize(current.afkTag))
                tag.isDefaultBackground = false
                tag.isShadowed = true
            }.also { player.hideEntity(this, it) }
        }
    }

    /** The endless AFK scene: random drone shots around the player, the first one with the name card. */
    private fun afkScene(player: Player, current: CinematicSettings): Scene {
        val random = Random(player.uniqueId.hashCode().toLong() xor System.nanoTime())
        val target = Target.of(player, TARGET_HEIGHT)
        return scene(AFK_SCENE) {
            transition = if (current.fade) Transition.FADE else Transition.Cut
            endTransition = null
            generate { index ->
                val drone = Shots.drone(player.world, target, current.shotSeconds * 20, random)
                if (index == 0 && current.nameCard) {
                    drone.copy(events = listOf(NAME_CARD_TICK to { viewer: Player -> nameCard(viewer) }))
                } else {
                    drone
                }
            }
        }
    }

    private fun nameCard(viewer: Player) {
        viewer.showTitle(
            net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.empty(),
                viewer.displayName(),
                net.kyori.adventure.title.Title.Times.times(Duration.ofMillis(1000), Duration.ofMillis(3000), Duration.ofMillis(1000)),
            ),
        )
    }

    private fun input(player: Player) {
        lastInput[player.uniqueId] = System.currentTimeMillis()
    }

    /** Joining counts as input. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = input(event.player)

    /** Leaving forgets the player. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastInput.remove(event.player.uniqueId)
        afkTags.remove(event.player.uniqueId)?.remove()
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

    /** Inventories. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventory(event: InventoryOpenEvent) {
        (event.player as? Player)?.let(::input)
    }

    private companion object {
        /** Scene name of the AFK camera (other plugins' scenes are left alone). */
        const val AFK_SCENE = "helix-afk"

        /** How often settings and opt-outs are refetched from the node. */
        const val FETCH_PERIOD_TICKS = 100L

        /** The camera looks at the chest/head of the player. */
        const val TARGET_HEIGHT = 1.3

        /** AFK tag height above the feet (above the name tag). */
        const val TAG_HEIGHT = 2.45

        /** When the name card appears in the first shot. */
        const val NAME_CARD_TICK = 20
    }
}
