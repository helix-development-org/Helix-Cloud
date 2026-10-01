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
 * whole server – other plugins use it with `depend: [HelixCinematic]` and [Cinematics.shared] – and runs the AFK
 * handling of the `helix.cinematic` addon. Mode `screen` (default): after [CinematicSettings.afkSeconds] without
 * input the [AfkScreen] dims the screen ("Du bist AFK – klicke oder bewege dich …") until the first input.
 * Mode `cinematic`, the **cinematic AFK camera**: after [CinematicSettings.afkSeconds]
 * without input on a task listed in the settings the player becomes the (spectating, invisible) camera, a
 * look-alike NPC with name, subtitle and cosmetics stands in their place, the screen is emptied, and an endless
 * scene of fly-ins ([Shots.flyIn]: straight at the NPC, slowly turning, faster or slower) separated by fades films
 * it. Others see an AFK tag above it. Any input ends it (the library's skip-on-input) and restores everything.
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

    /** Players whose Helix LabyMod addon shows the AFK screen / cinematic itself. */
    private val addonAfk = HashSet<UUID>()

    /** Players currently shown the AFK screen → tick of the last refresh. */
    private val screens = HashMap<UUID, Int>()
    private var ticks = 0

    /** Installs the library, connects to the node and starts the AFK watcher. */
    override fun onEnable() {
        cinematics = Cinematics.install(this)
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val token = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        val serviceId = System.getenv("HELIX_SERVICE_ID").orEmpty()
        server.pluginManager.registerEvents(this, this)
        server.messenger.registerOutgoingPluginChannel(this, AFK_CHANNEL)
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
        tickTask = server.scheduler.runTaskTimer(this, Runnable { tick() }, 20L, TICK_PERIOD.toLong())
        server.onlinePlayers.forEach { input(it) }
        logger.info("HelixCinematic enabled (task $task)")
    }

    /** Stops everything. */
    override fun onDisable() {
        fetchTask?.cancel()
        tickTask?.cancel()
        afkTags.values.forEach { it.remove() }
        afkTags.clear()
        screens.keys.mapNotNull { server.getPlayer(it) }.forEach { AfkScreen.hide(it) }
        screens.clear()
        if (::cinematics.isInitialized) Cinematics.uninstall(cinematics)
        client?.close()
        client = null
    }

    private fun tick() {
        ticks += TICK_PERIOD
        val current = settings
        val active = current.activeOn(task)
        val now = System.currentTimeMillis()
        if (!active || current.cinematic) screens.keys.toList().forEach { id -> server.getPlayer(id)?.let(::wake) }
        if (!active) addonAfk.toList().forEach { id -> server.getPlayer(id)?.let(::wake) }
        server.onlinePlayers.forEach { player ->
            val id = player.uniqueId
            if (id in addonAfk) {
                if (player.name.lowercase() in optedOut) return@forEach wake(player)
                afkTags[id]?.teleport(player.location.add(0.0, SCREEN_TAG_HEIGHT, 0.0).apply { yaw = 0f; pitch = 0f })
                return@forEach
            }
            screens[id]?.let { shownAt ->
                if (player.name.lowercase() in optedOut) return@forEach wake(player)
                if (ticks - shownAt >= AfkScreen.REFRESH_TICKS) {
                    AfkScreen.show(player, current, fadeIn = false)
                    screens[id] = ticks
                }
                afkTags[id]?.teleport(player.location.add(0.0, SCREEN_TAG_HEIGHT, 0.0).apply { yaw = 0f; pitch = 0f })
                return@forEach
            }
            val playback = cinematics.playing(player)
            if (playback != null) {
                if (playback.scene.name == AFK_SCENE && (!active || player.name.lowercase() in optedOut)) playback.stop()
                afkTags[player.uniqueId]?.teleport(player.location.add(0.0, TAG_HEIGHT, 0.0).apply { yaw = 0f; pitch = 0f })
                return@forEach
            }
            if (!active) return@forEach
            val idleMs = now - (lastInput[player.uniqueId] ?: now.also { lastInput[player.uniqueId] = it })
            if (idleMs < current.afkSeconds * 1000L || player.name.lowercase() in optedOut) return@forEach
            if (AFK_CHANNEL in player.listeningPluginChannels && player.gameMode != GameMode.SPECTATOR && !player.isSleeping) {
                startAddonAfk(player, current)
            } else if (!current.cinematic) {
                if (player.gameMode != GameMode.SPECTATOR && !player.isSleeping) showScreen(player, current)
            } else if (canStart(player)) {
                startAfk(player, current)
            }
        }
    }

    private fun showScreen(player: Player, current: CinematicSettings) {
        AfkScreen.show(player, current, fadeIn = true)
        screens[player.uniqueId] = ticks
        if (current.afkTag.isNotBlank()) {
            afkTags[player.uniqueId] = spawnTag(player, current, SCREEN_TAG_HEIGHT)
        }
    }

    /** The Helix LabyMod addon renders AFK itself (smoother, real camera flights): tell it to start. */
    private fun startAddonAfk(player: Player, current: CinematicSettings) {
        val mm = MiniMessage.miniMessage()
        val gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
        val message = """{"type":"start","mode":"${if (current.cinematic) "cinematic" else "screen"}",""" +
            """"title":${gson.serialize(mm.deserialize(current.afkTitle))},"subtitle":${gson.serialize(mm.deserialize(current.afkSubtitle))}}"""
        player.sendPluginMessage(this, AFK_CHANNEL, message.toByteArray(Charsets.UTF_8))
        addonAfk += player.uniqueId
        if (current.afkTag.isNotBlank()) afkTags[player.uniqueId] = spawnTag(player, current, SCREEN_TAG_HEIGHT)
    }

    /** First input after AFK: the screen goes away. */
    private fun wake(player: Player) {
        if (addonAfk.remove(player.uniqueId)) {
            player.sendPluginMessage(this, AFK_CHANNEL, """{"type":"stop"}""".toByteArray(Charsets.UTF_8))
            afkTags.remove(player.uniqueId)?.remove()
            return
        }
        if (screens.remove(player.uniqueId) == null) return
        AfkScreen.hide(player)
        afkTags.remove(player.uniqueId)?.remove()
    }

    private fun spawnTag(player: Player, current: CinematicSettings, height: Double): TextDisplay =
        player.world.spawn(player.location.add(0.0, height, 0.0), TextDisplay::class.java) { tag ->
            tag.isPersistent = false
            tag.billboard = Display.Billboard.CENTER
            tag.text(MiniMessage.miniMessage().deserialize(current.afkTag))
            tag.isDefaultBackground = false
            tag.isShadowed = true
        }.also { player.hideEntity(this, it) }

    private fun canStart(player: Player): Boolean =
        player.name.lowercase() !in optedOut &&
            player.gameMode != GameMode.SPECTATOR &&
            player.isOnGround && !player.isFlying && !player.isGliding && !player.isInsideVehicle && !player.isSleeping &&
            player.openInventory.topInventory.type == InventoryType.CRAFTING

    private fun startAfk(player: Player, current: CinematicSettings) {
        cinematics.play(player, afkScene(player, current)) {
            spectate = true
            standIn = true
            cleanScreen = true
            onFinish {
                afkTags.remove(player.uniqueId)?.remove()
                lastInput[player.uniqueId] = System.currentTimeMillis()
            }
        }
        if (current.afkTag.isNotBlank()) afkTags[player.uniqueId] = spawnTag(player, current, TAG_HEIGHT)
    }

    /** The endless AFK scene: random drone shots around the player, the first one with the name card. */
    private fun afkScene(player: Player, current: CinematicSettings): Scene {
        val random = Random(player.uniqueId.hashCode().toLong() xor System.nanoTime())
        val target = Target.of(player, TARGET_HEIGHT)
        return scene(AFK_SCENE) {
            transition = if (current.fade) Transition.FADE else Transition.Cut
            endTransition = null
            generate { index ->
                val drone = Shots.flyIn(player.world, target, minTicks = MIN_SHOT_TICKS, maxTicks = maxOf(MIN_SHOT_TICKS, current.shotSeconds * 20), random = random)
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
        if (player.uniqueId in screens || player.uniqueId in addonAfk) server.scheduler.runTask(this, Runnable { wake(player) })
    }

    /** Joining counts as input. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = input(event.player)

    /** Leaving forgets the player. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastInput.remove(event.player.uniqueId)
        screens.remove(event.player.uniqueId)
        addonAfk.remove(event.player.uniqueId)
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

        /** Channel of the Helix LabyMod addon's AFK handling. */
        const val AFK_CHANNEL = "helix:afk"

        /** Period of the AFK watcher in ticks. */
        const val TICK_PERIOD = 5

        /** AFK tag height above a real (standing) player – above the name tag and a subtitle. */
        const val SCREEN_TAG_HEIGHT = 2.55

        /** AFK tag height above the feet (above the stand-in's name line). */
        const val TAG_HEIGHT = 2.7

        /** Shortest fly-in (the longest is the configured shot length). */
        const val MIN_SHOT_TICKS = 60

        /** When the name card appears in the first shot. */
        const val NAME_CARD_TICK = 20
    }
}
