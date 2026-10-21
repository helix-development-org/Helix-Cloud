package de.tytoss.iguard.trace

import de.tytoss.iguard.EngineHarness
import de.tytoss.iguard.PLAYER
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.config.TraceConfig
import de.tytoss.iguard.model.ClientAction
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Minimal vanilla movement model (LivingEntity#travel on flat ground): ground acceleration
 * `speed * 0.216 / friction³`, air acceleration 0.02 (0.026 sprinting), friction `slipperiness * 0.91`
 * on ground and 0.91 in air, jump 0.42 plus the 0.2 sprint-jump impulse, gravity 0.08 with 0.98 drag.
 */
internal class VanillaWalker(var position: Vec3, private val floorY: Double, private val slipperiness: Double) {
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var velocityZ = 0.0
    var onGround = true
        private set

    /** Advances one tick and returns the position delta. */
    fun tick(forward: Boolean, sprint: Boolean, jump: Boolean, yaw: Float = 0f): Vec3 {
        val radians = Math.toRadians(yaw.toDouble())
        val groundBefore = onGround
        val friction = if (groundBefore) slipperiness * 0.91 else 0.91
        if (jump && groundBefore) {
            velocityY = 0.42
            if (sprint) {
                velocityX += -sin(radians) * 0.2
                velocityZ += cos(radians) * 0.2
            }
        }
        val attribute = if (sprint) 0.13 else 0.1
        val acceleration = if (groundBefore) attribute * (0.21600002 / slipperiness.pow(3)) else if (sprint) 0.026 else 0.02
        if (forward) {
            velocityX += -sin(radians) * 0.98 * acceleration
            velocityZ += cos(radians) * 0.98 * acceleration
        }
        var nextY = position.y + velocityY
        var landed = false
        if (nextY <= floorY) {
            nextY = floorY
            landed = true
            velocityY = 0.0
        }
        val delta = Vec3(velocityX, nextY - position.y, velocityZ)
        position += delta
        onGround = landed
        velocityX *= friction
        velocityZ *= friction
        velocityY = (velocityY - 0.08) * 0.98
        return delta
    }
}

/**
 * Generates the synthetic golden traces under `src/test/resources/traces` by running each scenario
 * through the real check engine with the real trace recorder. Run with the environment variable
 * `IGUARD_REGENERATE_TRACES=1` (see [GoldenTraceTest]); the expectations (`*.expect.json`) are
 * written by hand.
 */
internal object SyntheticTraces {
    /** Scenario name to its driver. */
    val scenarios: Map<String, (EngineHarness) -> Unit> = linkedMapOf(
        "legit-sprint-jump" to { harness -> sprintJump(harness, slipperiness = 0.6, surface = "GRASS_BLOCK") },
        "legit-ice" to { harness -> sprintJump(harness, slipperiness = 0.98, surface = "ICE") },
        "legit-ladder" to ::ladder,
        "cheat-speed-1.5x" to ::speed,
        "cheat-fly" to ::fly,
        "cheat-fastladder" to ::fastLadder,
        "cheat-jesus" to ::jesus,
    )

    /** Writes every scenario as `<name>.jsonl` into [directory]. */
    fun writeAll(directory: Path) {
        Files.createDirectories(directory)
        scenarios.forEach { (name, driver) -> write(directory, name, driver) }
    }

    private fun write(directory: Path, name: String, driver: (EngineHarness) -> Unit) {
        val scratch = Files.createTempDirectory("iguard-synthetic")
        var recorder: TraceHub? = null
        val harness = EngineHarness(
            extraObserver = object : FrameObserver {
                override fun wants(playerId: UUID): Boolean = recorder?.wants(playerId) ?: false

                override fun observe(trace: FrameTrace) {
                    recorder?.observe(trace)
                }
            },
        )
        val hub = TraceHub(
            scratch, TraceConfig(1L shl 26, 3_600, 100_000), AtomicReference(IGuardConfig.defaults().dynamic),
            "synthetic", { "Tester" }, { "V_1_21_11" }, Logger.getLogger("synthetic"), { harness.now },
        )
        recorder = hub
        val session = hub.start(PLAYER, name)
        driver(harness)
        session.stop()
        hub.shutdown()
        Files.move(session.file, directory.resolve("$name.jsonl"), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun startSprinting(harness: EngineHarness) {
        harness.send(ClientActionFrame(PLAYER, harness.nextSequence(), harness.now, harness.clientVersion, ClientAction.START_SPRINTING))
    }

    // Walk, sprint, sprint-jump, keep sprinting after landing, turn 45° and 90° while bunny-hopping,
    // then coast out — the legit patterns the speed model has to accept.
    private fun sprintJump(harness: EngineHarness, slipperiness: Double, surface: String) {
        harness.slipperiness = slipperiness
        harness.surface = surface
        val walker = VanillaWalker(harness.position, 64.0, slipperiness)
        fun run(ticks: Int, sprint: Boolean, jump: Boolean, forward: Boolean = true) = repeat(ticks) {
            walker.tick(forward = forward, sprint = sprint, jump = jump, yaw = harness.yaw)
            harness.tick(walker.position, walker.onGround)
        }
        repeat(5) { harness.tick(walker.position, onGround = true) }
        run(20, sprint = false, jump = false)
        startSprinting(harness)
        run(30, sprint = true, jump = false)
        run(60, sprint = true, jump = true)
        run(30, sprint = true, jump = false)
        harness.yaw = -45f
        run(30, sprint = true, jump = true)
        harness.yaw = -90f
        run(30, sprint = true, jump = true)
        run(25, sprint = false, jump = false, forward = false)
    }

    // Sprint on the ground with every horizontal move scaled by 1.5.
    private fun speed(harness: EngineHarness) {
        val walker = VanillaWalker(harness.position, 64.0, 0.6)
        repeat(5) { harness.tick(walker.position, onGround = true) }
        startSprinting(harness)
        var cheated = harness.position
        repeat(100) {
            val delta = walker.tick(forward = true, sprint = true, jump = false)
            cheated += Vec3(delta.x * 1.5, delta.y, delta.z * 1.5)
            harness.tick(cheated, walker.onGround)
        }
    }

    // Jump, then keep rising 0.1 blocks per tick.
    private fun fly(harness: EngineHarness) {
        var position = harness.position
        repeat(5) { harness.tick(position, onGround = true) }
        position += Vec3(0.0, 0.42, 0.0)
        harness.tick(position, onGround = false)
        repeat(60) {
            position += Vec3(0.0, 0.1, 0.05)
            harness.tick(position, onGround = false)
        }
    }

    // Vanilla climb: 0.1176 blocks per tick on a ladder.
    private fun ladder(harness: EngineHarness) = climb(harness, 0.1176)

    // Climb 0.3 blocks per tick on a ladder.
    private fun fastLadder(harness: EngineHarness) = climb(harness, 0.3)

    private fun climb(harness: EngineHarness, speed: Double) {
        var position = harness.position
        repeat(5) { harness.tick(position, onGround = true) }
        harness.tags = setOf("climbable")
        repeat(60) {
            position += Vec3(0.0, speed, 0.0)
            harness.tick(position, onGround = false)
        }
        harness.tags = emptySet()
    }

    // Walk across open water, feet resting on the surface, claiming ground.
    private fun jesus(harness: EngineHarness) {
        var position = harness.position
        repeat(5) { harness.tick(position, onGround = true) }
        harness.boxes = { emptyList() }
        harness.tags = setOf("liquid-surface")
        repeat(60) {
            position += Vec3(0.0, 0.0, 0.2)
            harness.tick(position, onGround = true)
        }
    }
}
