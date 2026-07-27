package de.tytoss.iguard.snapshot

import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.config.ExemptionConfig
import de.tytoss.iguard.config.SamplerConfig
import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.Vec3
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.entity.Boat
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Shulker
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerVelocityEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.BoundingBox
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-tick, budgeted main-thread sampler: captures each player's [EnvironmentFrame] (round-robin,
 * bounded per tick) into the [SnapshotStore] and grants event-driven exemptions (teleport, respawn,
 * world change, velocity) so the async workers never touch Bukkit themselves.
 */
class MainThreadSampler(
    private val plugin: JavaPlugin,
    private val store: SnapshotStore,
    private val exemptions: ExemptionManager,
    private val config: ExemptionConfig,
    private val samplerConfig: SamplerConfig
) : Listener {
    private val tick = AtomicLong()
    // Accessed only from the main thread (sample()), so a plain access-ordered LinkedHashMap is safe
    // and bounded — the previous ConcurrentHashMap grew unbounded with distinct block-states.
    private val collisionCache = object : LinkedHashMap<String, List<Box>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<Box>>) = size > 4096
    }
    // Per-player cache of the last scanned block neighborhood, so a player who stays within the same
    // block reuses the expensive 36-block collision scan instead of re-running it every sample.
    private val lastScan = HashMap<UUID, CachedScan>()
    private var cursor = 0
    private var task: BukkitTask? = null
    @Volatile private var lastSampleNanos = 0L
    @Volatile private var maximumSampleNanos = 0L
    @Volatile private var lastSampledPlayers = 0

    /** Registers the exemption listeners and starts the per-tick sampling task. */
    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::sample), 1L, 1L)
    }

    /** Cancels the sampling task. */
    fun stop() {
        task?.cancel()
        task = null
    }

    private fun sample() {
        val started = System.nanoTime()
        val currentTick = tick.incrementAndGet()
        val now = System.currentTimeMillis()
        val tps = Bukkit.getTPS().firstOrNull() ?: 20.0
        val players = Bukkit.getOnlinePlayers().toList()
        if (players.isEmpty()) {
            lastScan.clear()
            lastSampledPlayers = 0
            lastSampleNanos = System.nanoTime() - started
            return
        }
        // Budgeted round-robin: bound the per-tick main-thread cost regardless of player count. A
        // player is refreshed at worst every ceil(players / budget) ticks; the nanos deadline is a
        // hard safety cap that yields the tick early rather than blowing the TPS budget under load.
        val budget = samplerConfig.maxPlayersPerTick.coerceAtMost(players.size)
        val deadline = started + samplerConfig.maxNanosPerTick
        if (cursor >= players.size) cursor = 0
        var processed = 0
        while (processed < budget) {
            sampleOne(players[(cursor + processed) % players.size], now, currentTick, tps)
            processed++
            if (processed < budget && System.nanoTime() >= deadline) break
        }
        cursor = (cursor + processed) % players.size
        lastSampledPlayers = processed
        lastSampleNanos = System.nanoTime() - started
        maximumSampleNanos = maxOf(maximumSampleNanos, lastSampleNanos)
    }

    private fun sampleOne(player: Player, now: Long, currentTick: Long, tps: Double) {
        val location = player.location
        val world = location.world
        val box = player.boundingBox.toBox()
        val scan = scanFor(player.uniqueId, location)
        val collisions = scan.collisions
        val feet = Box(box.minX + 0.02, box.minY - 0.08, box.minZ + 0.02, box.maxX - 0.02, box.minY + 0.03, box.maxZ - 0.02)
        val body = Box(box.minX + 0.01, box.minY + 0.01, box.minZ + 0.01, box.maxX - 0.01, box.maxY - 0.01, box.maxZ - 0.01)
        val chunkLoaded = world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)
        val surface = location.block.getRelative(0, -1, 0).type
        val environmentTags = environmentTags(player, tps, location.block, scan.nearDynamic)
        val frame = EnvironmentFrame(
            now,
            currentTick,
            world.uid,
            Vec3(location.x, location.y, location.z),
            location.yaw,
            location.pitch,
            box,
            collisions,
            collisions.any(feet::intersects),
            collisions.any(body::intersects),
            player.gameMode.name,
            surface.name,
            surface.slipperiness(),
            player.walkSpeed,
            player.attribute(Attribute.MOVEMENT_SPEED, 0.1),
            player.attribute(Attribute.JUMP_STRENGTH, 0.42),
            player.attribute(Attribute.GRAVITY, 0.08),
            player.attribute(Attribute.STEP_HEIGHT, 0.6),
            player.eyeHeight,
            player.getPotionEffect(PotionEffectType.SPEED)?.amplifier ?: -1,
            player.getPotionEffect(PotionEffectType.JUMP_BOOST)?.amplifier ?: -1,
            player.ping,
            tps,
            player.velocity.let { Vec3(it.x, it.y, it.z) },
            environmentTags.any { it !in TELEMETRY_ONLY_TAGS },
            environmentTags,
            chunkLoaded
        )
        store.update(player.uniqueId, player.name, player.entityId, frame)
    }

    // Reuse the last collision scan while the player stays within the same block; only re-scan the
    // 36-block neighborhood when the integer block position (or world) changes.
    private fun scanFor(playerId: UUID, location: Location): CachedScan {
        val bx = location.blockX
        val by = location.blockY
        val bz = location.blockZ
        val worldUid = location.world.uid
        val cached = lastScan[playerId]
        if (cached != null && cached.worldUid == worldUid && cached.bx == bx && cached.by == by && cached.bz == bz) {
            return cached
        }
        val localScan = localEnvironment(location)
        return CachedScan(worldUid, bx, by, bz, localScan.collisions, localScan.nearDynamic).also { lastScan[playerId] = it }
    }

    /** Last and maximum per-tick sampling cost in microseconds (for /iguard status). */
    fun timingMicros() = lastSampleNanos / 1_000.0 to maximumSampleNanos / 1_000.0

    /** Players refreshed during the most recent tick. */
    fun sampledPlayers() = lastSampledPlayers

    private fun localEnvironment(location: Location): LocalScan {
        val world = location.world
        val result = ArrayList<Box>(32)
        var nearDynamic = false
        for (x in location.blockX - 1..location.blockX + 1) {
            for (z in location.blockZ - 1..location.blockZ + 1) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) {
                    nearDynamic = true
                    continue
                }
                for (y in location.blockY - 1..location.blockY + 2) {
                    val block = world.getBlockAt(x, y, z)
                    if (block.type.isDynamicEnvironment()) nearDynamic = true
                    val relative = relativeCollision(block)
                    relative.forEach { box ->
                        result += Box(box.minX + x, box.minY + y, box.minZ + z, box.maxX + x, box.maxY + y, box.maxZ + z)
                    }
                }
            }
        }
        return LocalScan(result, nearDynamic)
    }

    private fun relativeCollision(block: Block): List<Box> {
        if (block.type.isAir || block.isLiquid) return emptyList()
        val key = block.blockData.asString
        return collisionCache.computeIfAbsent(key) {
            block.collisionShape.boundingBoxes.map { box ->
                Box(
                    box.minX,
                    box.minY,
                    box.minZ,
                    box.maxX,
                    box.maxY,
                    box.maxZ
                )
            }.toList()
        }
    }

    private fun environmentTags(player: Player, tps: Double, block: Block, nearDynamic: Boolean): Set<String> = buildSet {
        val below = block.getRelative(0, -1, 0).type
        val type = block.type
        if (player.hasPermission("iguard.bypass")) add("bypass")
        if (player.isDead) add("dead")
        if (player.isSleeping) add("sleeping")
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) add("game-mode")
        if (player.allowFlight || player.isFlying) add("flight")
        if (player.isGliding) add("elytra")
        if (player.isRiptiding) add("riptide")
        if (player.isInsideVehicle) add("vehicle")
        if (block.isLiquid) add("liquid")
        if (Tag.CLIMBABLE.isTagged(type)) add("climbable")
        if (type == Material.COBWEB) add("cobweb")
        // Beds bounce (a legit upward impulse while "airborne"); the bed is at the player's feet, so
        // both the occupied block and the one below must be checked.
        if (Tag.BEDS.isTagged(type) || Tag.BEDS.isTagged(below)) add("bed")
        // Boats, minecarts and shulkers are collidable ENTITIES: a player standing on one is
        // legitimately onGround with no block collision underneath, which the block-based support
        // model cannot see — without this tag they hover-fly/nofall-flag within a second.
        if (standsOnEntity(player)) add("entity-support")
        if (below == Material.SLIME_BLOCK) add("slime")
        if (below == Material.HONEY_BLOCK) add("honey")
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) add("soul-speed")
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) add("levitation")
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) add("slow-falling")
        if (tps < config.lowTpsThreshold) add("low-tps")
        if (nearDynamic) add("dynamic-block")
    }

    private fun standsOnEntity(player: Player): Boolean {
        val box = player.boundingBox
        val search = BoundingBox(box.minX - 0.6, box.minY - 1.0, box.minZ - 0.6, box.maxX + 0.6, box.maxY + 0.2, box.maxZ + 0.6)
        return player.world.getNearbyEntities(search) { it !== player && (it is Boat || it is Minecart || it is Shulker) }.isNotEmpty()
    }

    /** Grants the teleport grace window. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = exempt(event.player, config.teleportMillis, "teleport")

    /** Grants the respawn grace window. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = exempt(event.player, config.respawnMillis, "respawn")

    /** Grants the world-change grace window. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = exempt(event.player, config.worldChangeMillis, "world-change")

    /** Grants the knockback/velocity grace window. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVelocity(event: PlayerVelocityEvent) = exempt(event.player, config.velocityMillis, "velocity")

    /** Clears the quitting player's snapshot, exemption and scan-cache state. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        store.remove(event.player.uniqueId)
        exemptions.remove(event.player.uniqueId)
        lastScan.remove(event.player.uniqueId)
    }

    private fun exempt(player: Player, millis: Long, reason: String) {
        exemptions.exempt(player.uniqueId, Duration.ofMillis(millis.coerceAtLeast(1)), reason)
    }
}

// Tags that describe server health rather than a per-player physics modifier. They must NOT force a
// movement exemption (that would disable detection server-wide under load); they stay in the tag set
// for telemetry and are consumed as tolerance hints by the evaluator instead.
private val TELEMETRY_ONLY_TAGS = setOf("low-tps")

private data class LocalScan(val collisions: List<Box>, val nearDynamic: Boolean)

private data class CachedScan(
    val worldUid: UUID,
    val bx: Int,
    val by: Int,
    val bz: Int,
    val collisions: List<Box>,
    val nearDynamic: Boolean
)

private fun BoundingBox.toBox() = Box(minX, minY, minZ, maxX, maxY, maxZ)

private fun Player.attribute(attribute: Attribute, fallback: Double) = getAttribute(attribute)?.value ?: fallback

private fun Material.slipperiness() = when (this) {
    Material.ICE, Material.PACKED_ICE, Material.FROSTED_ICE -> 0.98
    Material.BLUE_ICE -> 0.989
    Material.SLIME_BLOCK -> 0.8
    else -> 0.6
}

private fun Material.isDynamicEnvironment() = when (this) {
    Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON,
    Material.BUBBLE_COLUMN, Material.COBWEB, Material.POWDER_SNOW -> true
    else -> false
}
