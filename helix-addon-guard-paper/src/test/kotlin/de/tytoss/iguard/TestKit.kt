package de.tytoss.iguard

import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.api.IGuardCheckResult
import de.tytoss.iguard.api.IGuardFrameReport
import de.tytoss.iguard.check.AlertSink
import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.check.Enforcement
import de.tytoss.iguard.check.SetbackSink
import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.ClientTickFrame
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.EnvironmentTags
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.PacketFrame
import de.tytoss.iguard.model.SafePosition
import de.tytoss.iguard.model.Vec3
import de.tytoss.iguard.model.ViolationRecord
import de.tytoss.iguard.notify.NotificationService
import de.tytoss.iguard.snapshot.SnapshotStore
import de.tytoss.iguard.storage.MemoryGuardStore
import de.tytoss.iguard.trace.FrameObserver
import de.tytoss.iguard.trace.FrameTrace
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.math.floor

/** Test player (a Java uuid: Bedrock uuids have zero high bits and skip movement checks). */
internal val PLAYER: UUID = UUID.fromString("7a3f2c10-5b1e-4c8d-9a2b-3c4d5e6f7a80")

/** Second player used as attack target. */
internal val TARGET: UUID = UUID.fromString("8b4e3d21-6c2f-4d9e-8b3c-4d5e6f708192")

/** Test world. */
internal val WORLD: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")

/** A 7x7 floor slab whose top face is at [floorY], centred on the block of [center]. */
internal fun floorBoxes(center: Vec3, floorY: Double = 64.0): List<Box> {
    val x = floor(center.x)
    val z = floor(center.z)
    return listOf(Box(x - 3, floorY - 1, z - 3, x + 4, floorY, z + 4))
}

/** A sampled environment at [position] with the given collision boxes and tags. */
internal fun environment(
    at: Long,
    position: Vec3,
    tags: Set<String> = emptySet(),
    boxes: List<Box> = floorBoxes(position),
    slipperiness: Double = 0.6,
    surface: String = "GRASS_BLOCK",
    ping: Int = 0,
    tps: Double = 20.0,
    world: UUID = WORLD,
): EnvironmentFrame {
    val box = Box(position.x - 0.3, position.y, position.z - 0.3, position.x + 0.3, position.y + 1.8, position.z + 0.3)
    val feet = Box(box.minX + 0.02, box.minY - 0.08, box.minZ + 0.02, box.maxX - 0.02, box.minY + 0.03, box.maxZ - 0.02)
    val body = Box(box.minX + 0.01, box.minY + 0.01, box.minZ + 0.01, box.maxX - 0.01, box.maxY - 0.01, box.maxZ - 0.01)
    return EnvironmentFrame(
        capturedAt = at,
        tick = at / 50,
        worldId = world,
        position = position,
        yaw = 0f,
        pitch = 0f,
        entityBox = box,
        collisionBoxes = boxes,
        supportingCollision = boxes.any(feet::intersects),
        colliding = boxes.any(body::intersects),
        gameMode = "SURVIVAL",
        surface = surface,
        surfaceSlipperiness = slipperiness,
        walkSpeed = 0.2f,
        movementSpeed = 0.1,
        jumpStrength = 0.42,
        gravity = 0.08,
        stepHeight = 0.6,
        eyeHeight = 1.62,
        speedAmplifier = -1,
        jumpAmplifier = -1,
        ping = ping,
        tps = tps,
        velocity = Vec3(0.0, 0.0, 0.0),
        exemptEnvironment = tags.any { it !in EnvironmentTags.NON_EXEMPTING },
        environmentTags = tags,
        chunkLoaded = true,
    )
}

/**
 * A synchronous [CheckEngine] with in-memory sinks and a controllable clock: frames are processed on
 * the calling thread, every frame's report is collected in [reports].
 */
internal class EngineHarness(
    tweak: (DynamicConfig) -> DynamicConfig = { it },
    extraObserver: FrameObserver? = null,
) {
    var now = 1_760_000_000_000L
    val config = IGuardConfig.defaults("test")
    val snapshots = SnapshotStore()
    val exemptions = ExemptionManager { now }
    val dynamic = AtomicReference(tweak(config.dynamic))
    val store = MemoryGuardStore()
    val alerts = CopyOnWriteArrayList<ViolationRecord>()
    val setbackRequests = CopyOnWriteArrayList<String>()
    var recentSetback = false
    val reports = CopyOnWriteArrayList<IGuardFrameReport>()
    var clientVersion = "V_1_21_11"
    var position = Vec3(0.5, 64.0, 0.5)
    var tags: Set<String> = emptySet()
    var boxes: (Vec3) -> List<Box> = { floorBoxes(it) }
    var slipperiness = 0.6
    var surface = "GRASS_BLOCK"
    var yaw = 0f
    var pitch = 0f
    private var sequence = 0L
    private var interaction = 0
    private val logger = Logger.getLogger("IGuard-Test")

    val engine = CheckEngine(
        1, 1024, snapshots, exemptions, config.exemptions, config.detection, config.sanctions, dynamic, "test", store,
        AlertSink { alerts += it },
        object : SetbackSink {
            override fun request(playerId: UUID, safe: SafePosition, reason: String) {
                setbackRequests += reason
            }

            override fun recentSetback(playerId: UUID, now: Long): Boolean = recentSetback
        },
        Enforcement { _, _, _, _, _ -> },
        NotificationService(dynamic, logger),
        logger,
        object : FrameObserver {
            override fun wants(playerId: UUID): Boolean = true

            override fun observe(trace: FrameTrace) {
                if (trace.frame.playerId == PLAYER) reports += trace.toReport()
                extraObserver?.takeIf { it.wants(trace.frame.playerId) }?.observe(trace)
            }
        },
    )

    /** Next per-player sequence number (frames must use consecutive numbers, gaps reset the motion model). */
    fun nextSequence(): Long = ++sequence

    /** Next block-interaction sequence number. */
    fun nextInteraction(): Int = ++interaction

    /** Publishes a fresh environment sample for the player at [at] (default: current position/tags). */
    fun sample(at: Vec3 = position, sampleTags: Set<String> = tags, ping: Int = 0, tps: Double = 20.0) {
        snapshots.update(PLAYER, "Tester", 7, environment(now, at, sampleTags, boxes(at), slipperiness, surface, ping, tps))
    }

    /** Processes [frame] synchronously. */
    fun send(frame: PacketFrame) = engine.processNow(frame)

    /** Samples, then sends a position(+rotation) packet to [to]. */
    fun move(to: Vec3, onGround: Boolean, positionChanged: Boolean = true, resample: Boolean = true) {
        position = to
        if (resample) sample()
        send(MovementFrame(PLAYER, nextSequence(), now, clientVersion, positionChanged, true, to, yaw, pitch, onGround))
    }

    /** Sends the 1.21.2+ tick-end packet. */
    fun tickEnd() = send(ClientTickFrame(PLAYER, nextSequence(), now, clientVersion))

    /** One client tick: movement to [to], tick end, clock +50 ms. */
    fun tick(to: Vec3, onGround: Boolean) {
        move(to, onGround)
        tickEnd()
        now += 50
    }

    /** Current violation level of [checkId]. */
    fun vl(checkId: String): Double = engine.violationLevels(PLAYER)[checkId] ?: 0.0

    /** Every result of [checkId] reported so far. */
    fun results(checkId: String): List<IGuardCheckResult> = reports.flatMap { it.results }.filter { it.checkId == checkId }

    /** Whether [checkId] failed at least once. */
    fun failed(checkId: String): Boolean = results(checkId).any { it.failed }
}
