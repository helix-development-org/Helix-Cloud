package de.tytoss.iguard.replay

import de.tytoss.iguard.storage.GuardStore
import de.tytoss.iguard.storage.ReplayFrameRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.fsqrt.rune.Pos3d
import org.fsqrt.rune.Rune
import org.fsqrt.rune.RuneBlock
import org.helix.api.i18n.NodeTranslations
import org.helix.api.message.LegacyToMini
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Watchable incident replay: rebuilds the terrain around a recorded incident in an isolated void world
 * (via the Rune snapshot model) and plays the recorded movements back on a packet-only player NPC that
 * wears the recorded player's real skin and name, which the admin watches with play/pause/speed, an
 * optional chase camera, a path trail, and an action-bar scrubber. All Bukkit work runs on the main
 * thread; the region paste is batched across ticks and cleared again on stop; the NPC is unicast to the
 * watching admin only.
 */
class ReplayService(
    private val plugin: JavaPlugin,
    private val storage: GuardStore,
    private val scope: CoroutineScope,
    private val translations: NodeTranslations,
) {
    private val miniMessage = MiniMessage.miniMessage()

    private class Session(
        val viewerId: UUID,
        val world: World,
        val region: Pair<Pos3d, Pos3d>,
        val npc: ReplayNpc,
        val frames: List<ReplayFrameRow>,
        var speed: Double,
        val prevMode: GameMode,
        val prevLocation: Location,
    ) {
        val startMillis = frames.first().at
        val durationMillis = (frames.last().at - frames.first().at).coerceAtLeast(1)
        var task: BukkitTask? = null
        var elapsed = 0.0
        var paused = false

        // free camera by default — the chase cam stays available via the Follow control
        var follow = false
        var trail = true
        var ticks = 0L
    }

    @Volatile private var world: World? = null
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /** Loads an incident's frames, rebuilds the scene in the replay world and starts playback. */
    fun startReplay(admin: Player, incidentId: UUID, speed: Double) {
        scope.launch {
            val frames = storage.replayFrames(incidentId)
            val worldUid = storage.incidentWorld(incidentId)
            val who = storage.incidentPlayer(incidentId)
            // Resolve the skin off the main thread (may hit Mojang) before touching Bukkit.
            val profile = ReplayNpc.profileFor(who?.first ?: admin.uniqueId, who?.second ?: "replay")
            main {
                if (frames.size < 2) { admin.sendMessage(chat(admin, "replay.no-data")); return@main }
                val rw = ensureWorld() ?: run { admin.sendMessage(chat(admin, "replay.world-unavailable")); return@main }
                stop(admin)
                val src = worldUid?.let { Bukkit.getWorld(it) }
                val (minP, maxP) = bounds(frames, src)
                plugin.logger.info("Replay $incidentId: ${frames.size} frames, srcWorld=${src?.name ?: "MISSING(uid=$worldUid)"}, region $minP..$maxP")
                admin.sendMessage(chat(admin, "replay.building", "frames" to frames.size.toString()))
                pasteRegionThen(src, rw, minP, maxP) {
                    val f0 = frames.first()
                    val prevMode = admin.gameMode
                    val prevLocation = admin.location.clone()
                    admin.gameMode = GameMode.SPECTATOR
                    admin.teleport(Location(rw, f0.x + 3.0, f0.y + 1.5, f0.z + 3.0, f0.yaw + 180f, 20f))
                    val npc = ReplayNpc(admin, profile)
                    npc.spawn(f0.x, f0.y, f0.z, f0.yaw, f0.pitch)
                    val session = Session(admin.uniqueId, rw, minP to maxP, npc, frames, speed.coerceIn(0.1, 10.0), prevMode, prevLocation)
                    sessions[admin.uniqueId] = session
                    admin.sendMessage(
                        chat(admin, "replay.ready")
                            .append(control(admin, "replay.button.pause", "/iguard replay pause")).append(Component.text(" "))
                            .append(control(admin, "replay.button.speed", "/iguard replay speed ${"%.1f".format(session.speed * 2)}")).append(Component.text(" "))
                            .append(control(admin, "replay.button.follow", "/iguard replay follow")).append(Component.text(" "))
                            .append(control(admin, "replay.button.trail", "/iguard replay trail")).append(Component.text(" "))
                            .append(control(admin, "replay.button.stop", "/iguard replay stop")),
                    )
                    session.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick(session) }, 1L, 1L)
                }
            }
        }
    }

    /** Toggles pause for the admin's running replay. */
    fun pause(admin: Player) { sessions[admin.uniqueId]?.let { it.paused = !it.paused; admin.sendMessage(chat(admin, if (it.paused) "replay.paused" else "replay.resumed")) } }

    /** Sets the playback speed (clamped to 0.1..10x). */
    fun setSpeed(admin: Player, speed: Double) { sessions[admin.uniqueId]?.let { it.speed = speed.coerceIn(0.1, 10.0); admin.sendMessage(chat(admin, "replay.speed", "speed" to it.speed.toString())) } }

    /** Toggles the chase camera that follows the replay NPC. */
    fun toggleFollow(admin: Player) { sessions[admin.uniqueId]?.let { it.follow = !it.follow; admin.sendMessage(chat(admin, if (it.follow) "replay.follow-on" else "replay.follow-off")) } }

    /** Toggles the particle trail along the replayed path. */
    fun toggleTrail(admin: Player) { sessions[admin.uniqueId]?.let { it.trail = !it.trail; admin.sendMessage(chat(admin, if (it.trail) "replay.trail-on" else "replay.trail-off")) } }

    /** Ends the admin's replay session and restores their previous state. */
    fun stop(admin: Player) = sessions.remove(admin.uniqueId)?.let { endSession(it, admin) }

    /** Called when a watching admin disconnects — tear down without needing an online player. */
    fun handleQuit(playerId: UUID) { sessions.remove(playerId)?.let { endSession(it, Bukkit.getPlayer(playerId)) } }

    /** Ends every running replay session (plugin disable). */
    fun stopAll() { sessions.keys.toList().forEach { id -> sessions.remove(id)?.let { endSession(it, Bukkit.getPlayer(id)) } } }

    private fun endSession(session: Session, admin: Player?) {
        session.task?.cancel()
        session.npc.despawn()
        if (admin != null && admin.isOnline) {
            admin.gameMode = session.prevMode
            admin.teleport(session.prevLocation)
            admin.sendActionBar(Component.empty())
        }
        clearRegion(session.world, session.region.first, session.region.second)
    }

    private fun tick(session: Session) {
        val admin = Bukkit.getPlayer(session.viewerId) ?: return handleQuit(session.viewerId)
        if (!session.paused) {
            session.elapsed += 50.0 * session.speed
            if (session.startMillis + session.elapsed.toLong() > session.frames.last().at) session.elapsed = 0.0 // loop
        }
        val target = session.startMillis + session.elapsed.toLong()
        val frame = session.frames.lastOrNull { it.at <= target } ?: session.frames.first()
        session.npc.move(frame.x, frame.y, frame.z, frame.yaw, frame.pitch, frame.onGround)
        if (session.follow) chaseCamera(admin, session, frame)
        if (session.trail && session.ticks % 6L == 0L) drawTrail(admin, session)
        scrubber(admin, session)
        session.ticks++
    }

    /** Places the spectating admin a few blocks behind the NPC, looking the way the NPC faces. */
    private fun chaseCamera(admin: Player, session: Session, frame: ReplayFrameRow) {
        val rad = Math.toRadians(frame.yaw.toDouble())
        val camX = frame.x + sin(rad) * 4.0
        val camZ = frame.z - cos(rad) * 4.0
        val camY = frame.y + 2.2
        admin.teleport(Location(session.world, camX, camY, camZ, frame.yaw, 18f))
    }

    /** Draws a fading dust trail along the recorded path so the whole route is visible at a glance. */
    private fun drawTrail(admin: Player, session: Session) {
        val dust = Particle.DustOptions(Color.fromRGB(0xFF, 0x33, 0x44), 1.0f)
        val step = (session.frames.size / 120).coerceAtLeast(1)
        var i = 0
        while (i < session.frames.size) {
            val f = session.frames[i]
            admin.spawnParticle(Particle.DUST, f.x, f.y + 1.0, f.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
            i += step
        }
    }

    private fun scrubber(admin: Player, session: Session) {
        val elapsedS = (session.elapsed / 1000.0).coerceAtLeast(0.0)
        val totalS = session.durationMillis / 1000.0
        val filled = ((session.elapsed / session.durationMillis) * 20).toInt().coerceIn(0, 20)
        val bar = "█".repeat(filled) + "░".repeat(20 - filled)
        val head = if (session.paused) "⏸" else "▶"
        val text = screen(
            admin, "replay.scrubber",
            "elapsed" to "%.1f".format(elapsedS),
            "total" to "%.1f".format(totalS),
            "speed" to "%.1f".format(session.speed),
        )
        admin.sendActionBar(
            Component.text("$head ").color(net.kyori.adventure.text.format.NamedTextColor.RED)
                .append(Component.text(bar).color(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(text),
        )
    }

    private fun ensureWorld(): World? {
        world?.let { return it }
        Bukkit.getWorld("iguard_replay")?.let { world = it; return it }
        val created = runCatching {
            WorldCreator("iguard_replay").generator(VoidChunkGenerator()).createWorld()
        }.getOrNull()
        world = created
        return created
    }

    /**
     * A ground-anchored window centred on the replay's spawn point (where the NPC starts and the admin
     * is teleported), capped so the paste stays cheap. We deliberately do NOT track the full movement
     * bounding box: a fly/speed cheat can roam hundreds of blocks (or fly to y=2000), and pasting that
     * corner would leave the actual playback area empty. Vertically we anchor to the real terrain at
     * spawn via getHighestBlockYAt so the ground the player stood on is always captured, no matter how
     * high the recorded cheat later flew.
     */
    private fun bounds(frames: List<ReplayFrameRow>, src: World?): Pair<Pos3d, Pos3d> {
        val f0 = frames.first()
        val cx = f0.x.toInt(); val cz = f0.z.toInt()
        val half = 24
        val minX = cx - half; val maxX = cx + half
        val minZ = cz - half; val maxZ = cz + half
        val floor = src?.minHeight ?: -64
        val ceil = (src?.maxHeight ?: 320) - 1
        val groundTop = src?.getHighestBlockYAt(cx, cz) ?: f0.y.toInt()
        val minY = (groundTop - 6).coerceIn(floor, ceil)
        val maxY = (groundTop + 30).coerceIn(floor, ceil)
        return Pos3d(minX, minY, minZ) to Pos3d(maxX, maxY, maxZ)
    }

    /** Export the region from [src] into a Rune snapshot, then paste it into [dst] batched, then run [done]. */
    private fun pasteRegionThen(src: World?, dst: World, minP: Pos3d, maxP: Pos3d, done: () -> Unit) {
        if (src == null) { plugin.logger.warning("Replay: source world unavailable, skipping terrain"); done(); return }
        val rune = exportRegion(src, minP, maxP)
        val entries = rune.blocks.entries.toList()
        plugin.logger.info("Replay: pasting ${entries.size} blocks into ${dst.name}")
        var i = 0
        val batch = 3000
        lateinit var placer: Runnable
        placer = Runnable {
            var n = 0
            while (i < entries.size && n < batch) {
                val (off, b) = entries[i]
                runCatching {
                    val block = dst.getBlockAt(minP.x + off.x, minP.y + off.y, minP.z + off.z)
                    block.type = Material.valueOf(b.type)
                    if (b.state.isNotEmpty()) block.blockData = Bukkit.createBlockData(b.state)
                }
                i++; n++
            }
            if (i < entries.size) Bukkit.getScheduler().runTask(plugin, placer) else done()
        }
        Bukkit.getScheduler().runTask(plugin, placer)
    }

    /** Reverts the pasted region back to air, batched across ticks so a large box never stalls a tick. */
    private fun clearRegion(dst: World, minP: Pos3d, maxP: Pos3d) {
        val positions = ArrayList<Triple<Int, Int, Int>>()
        for (x in minP.x..maxP.x) for (y in minP.y..maxP.y) for (z in minP.z..maxP.z) positions += Triple(x, y, z)
        var i = 0
        val batch = 3000
        lateinit var clearer: Runnable
        clearer = Runnable {
            var n = 0
            while (i < positions.size && n < batch) {
                val (x, y, z) = positions[i]
                runCatching { dst.getBlockAt(x, y, z).type = Material.AIR }
                i++; n++
            }
            if (i < positions.size) Bukkit.getScheduler().runTask(plugin, clearer)
        }
        Bukkit.getScheduler().runTask(plugin, clearer)
    }

    /** Uses the Rune snapshot model to capture the region's blocks (relative to min). */
    private fun exportRegion(src: World, minP: Pos3d, maxP: Pos3d): Rune {
        val blocks = HashMap<Pos3d, RuneBlock>()
        for (x in minP.x..maxP.x) for (y in minP.y..maxP.y) for (z in minP.z..maxP.z) {
            val block = src.getBlockAt(x, y, z)
            if (block.type == Material.AIR) continue
            blocks[Pos3d(x, y, z) - minP] = RuneBlock(block.type.toString(), block.blockData.asString, emptySet())
        }
        return Rune(1, blocks, emptySet())
    }

    private fun main(action: () -> Unit) = Bukkit.getScheduler().runTask(plugin, Runnable(action))

    private fun locale(player: Player): String = player.locale().language

    /** A chat message (network prefix included) resolved in the admin's language, rendered. */
    private fun chat(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.text(player.name, locale(player), key, *params))

    /** Prefix-free screen/ActionBar text resolved in the admin's language, rendered. */
    private fun screen(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.screen(player.name, locale(player), key, *params))

    /** A clickable `[label]` replay control button; the label is resolved prefix-free. */
    private fun control(player: Player, labelKey: String, command: String): Component =
        Component.text("[").color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
            .append(screen(player, labelKey).color(net.kyori.adventure.text.format.NamedTextColor.AQUA))
            .append(Component.text("]").color(net.kyori.adventure.text.format.NamedTextColor.AQUA))
            .clickEvent(ClickEvent.runCommand(command))

    private fun render(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text)).decoration(TextDecoration.ITALIC, false)
}
