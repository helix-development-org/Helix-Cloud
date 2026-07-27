package de.tytoss.iguard.check

import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.Vec3
import de.tytoss.iguard.profile.VersionProfile
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovementEvaluatorTest {
    private val evaluator = MovementEvaluator()
    private val profile = VersionProfile(0.08, 0.98, 0.42, 0.29, 0.36, 1.3, 3.0)

    @Test
    fun `vanilla ground acceleration remains below speed projection`() {
        val state = PlayerState()
        var z = 0.0

        for (delta in listOf(0.0980, 0.1515, 0.1807, 0.1967, 0.2054, 0.2159)) {
            state.positionGapTicks = 1
            z += delta
            val current = movement(Vec3(0.0, 0.0, z), onGround = true)
            val evaluation = evaluator.evaluate(current, Vec3(0.0, 0.0, delta), ground(), profile, state)
            assertFalse(evaluation.failed("movement.speed.a"), "vanilla delta $delta was rejected")
            evaluator.acceptHorizontal(Vec3(0.0, 0.0, delta), ground(), state, evaluation.supporting)
        }
    }

    @Test
    fun `two tick sprint delta uses accumulated client ticks`() {
        val state = PlayerState().apply {
            sprinting = true
            predictedHorizontal = 0.153
            positionGapTicks = 2
        }
        val delta = Vec3(0.0, 0.0, 0.4806)
        val evaluation = evaluator.evaluate(
            movement(delta, onGround = true),
            delta,
            ground(),
            profile,
            state
        )

        assertFalse(evaluation.failed("movement.speed.a"))
    }

    @Test
    fun `sprint jump includes vanilla horizontal takeoff impulse`() {
        val state = PlayerState().apply {
            sprinting = true
            predictedHorizontal = 0.153
            positionGapTicks = 2
            airTicks = 0
        }
        val delta = Vec3(0.0, 0.42, 0.4806)
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        val evaluation = evaluator.evaluate(
            movement(delta, onGround = false),
            delta,
            air,
            profile,
            state
        )

        assertFalse(evaluation.failed("movement.speed.a"))
    }

    @Test
    fun `sprint jump preserves vanilla bunny hop momentum`() {
        val state = PlayerState().apply {
            sprinting = true
            predictedHorizontal = 0.225
            lastHorizontalDelta = 0.4122
            positionGapTicks = 1
            airTicks = 0
        }
        val delta = Vec3(0.0, 0.42, 0.6122)
        val staleGroundSnapshot = ground()
        val evaluation = evaluator.evaluate(
            movement(delta, onGround = false),
            delta,
            staleGroundSnapshot,
            profile,
            state
        )

        assertFalse(evaluation.failed("movement.speed.a"))
    }

    @Test
    fun `single tick speed cheat exceeds steady sprint projection`() {
        val state = PlayerState().apply {
            sprinting = true
            predictedHorizontal = 0.153
            positionGapTicks = 1
        }
        val delta = Vec3(0.0, 0.0, 0.4806)
        val evaluation = evaluator.evaluate(
            movement(delta, onGround = true),
            delta,
            ground(),
            profile,
            state
        )

        assertTrue(evaluation.failed("movement.speed.a"))
    }

    @Test
    fun `sustained speed cheat flags every tick and its weight scales with the offset`() {
        val state = PlayerState().apply {
            sprinting = false
            predictedHorizontal = 0.08
            positionGapTicks = 1
        }

        // a small offset over the limit accrues little weight...
        val smallDelta = Vec3(0.0, 0.0, 0.14)
        val small = evaluator.evaluate(movement(Vec3(0.0, 1.0, 0.14), onGround = false), smallDelta, ground(), profile, state)
        val smallWeight = small.failures.firstOrNull { it.checkId == "movement.speed.a" }?.weight ?: 0.0

        // ...while a blatant offset accrues far more, on the same clean state
        val bigState = PlayerState().apply { predictedHorizontal = 0.08; positionGapTicks = 1 }
        val bigDelta = Vec3(0.0, 0.0, 0.6)
        val big = evaluator.evaluate(movement(Vec3(0.0, 1.0, 0.6), onGround = false), bigDelta, ground(), profile, bigState)
        val bigWeight = big.failures.first { it.checkId == "movement.speed.a" }.weight

        assertTrue(small.failed("movement.speed.a"))
        assertTrue(bigWeight > smallWeight, "blatant offset ($bigWeight) must outweigh the borderline one ($smallWeight)")
    }

    @Test
    fun `knockback residual widens the speed window so a knocked-back player is not flagged`() {
        val state = PlayerState().apply {
            sprinting = false
            predictedHorizontal = 0.1
            positionGapTicks = 1
            horizontalUncertainty = 0.6
        }
        val delta = Vec3(0.0, 0.0, 0.5)
        val evaluation = evaluator.evaluate(movement(Vec3(0.0, 1.0, 0.5), onGround = false), delta, ground(), profile, state)

        assertFalse(evaluation.failed("movement.speed.a"), "0.5 move within a 0.6 knockback window must be legal")
    }

    @Test
    fun `vanilla jump arc does not trigger fly`() {
        val state = PlayerState()
        var y = 0.0
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")

        for (dy in listOf(0.42, 0.3332, 0.2481, 0.1648, 0.0831, 0.0030, -0.0754, -0.1523)) {
            state.positionGapTicks = 1
            y += dy
            val current = movement(Vec3(0.0, y, 0.0), onGround = false)
            val evaluation = evaluator.evaluate(current, Vec3(0.0, dy, 0.0), air, profile, state)
            assertFalse(evaluation.failed("movement.fly.a"), "vanilla dy $dy was rejected")
            evaluator.acceptVertical(Vec3(0.0, dy, 0.0), air, state, evaluation.supporting)
            state.lastVerticalDelta = dy
            state.airTicks++
        }
    }

    @Test
    fun `vanilla jump arc ignores stale grounded snapshot`() {
        val state = PlayerState()
        var y = 0.0
        val staleGroundSnapshot = ground()

        for (dy in listOf(0.42, 0.3332, 0.2481, 0.1648, 0.0831, 0.0030, -0.0754, -0.1523)) {
            state.positionGapTicks = 1
            y += dy
            val current = movement(Vec3(0.0, y, 0.0), onGround = false)
            val evaluation = evaluator.evaluate(current, Vec3(0.0, dy, 0.0), staleGroundSnapshot, profile, state)
            assertFalse(evaluation.failed("movement.fly.a"), "vanilla dy $dy was rejected with a stale snapshot")
            evaluator.acceptVertical(Vec3(0.0, dy, 0.0), staleGroundSnapshot, state, evaluation.supporting)
            state.lastVerticalDelta = dy
            state.airTicks++
        }
    }

    @Test
    fun `repeated ground spoof while falling triggers nofall`() {
        val state = PlayerState().apply {
            airTicks = 6
            positionGapTicks = 1
        }
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        val delta = Vec3(0.0, -0.3, 0.0)
        val evaluation = evaluator.evaluate(
            movement(Vec3(0.0, 4.7, 0.0), onGround = true),
            delta,
            air,
            profile,
            state
        )

        assertTrue(evaluation.failed("movement.nofall.a"))
    }

    @Test
    fun `positionless ground spoof triggers nofall`() {
        val state = PlayerState().apply {
            airTicks = 6
            lastVerticalDelta = -0.3
        }
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")

        assertTrue(evaluator.groundSpoofFailure(onGround = true, Vec3(0.0, 1.0, 0.0), air, state)?.checkId == "movement.nofall.a")
    }

    @Test
    fun `non finite position is deterministic bad packet evidence`() {
        val failure = evaluator.badPacketFailure(movement(Vec3(Double.NaN, 1.0, 0.0), onGround = false))

        assertEquals("protocol.badpackets.a", failure?.checkId)
        assertTrue(failure?.deterministic == true)
    }

    @Test
    fun `ignored velocity produces velocity evidence after six ticks`() {
        val state = PlayerState().apply {
            clientTick = 16
            pendingVelocityTick = 10
            pendingVelocity = Vec3(0.5, 0.4, 0.0)
            velocityObservedHorizontal = 0.01
            velocityObservedVertical = 0.0
        }

        assertEquals("movement.velocity.a", evaluator.velocityFailure(state, ground())?.checkId)
    }

    @Test
    fun `wall climb after sustained air time triggers spider`() {
        val state = PlayerState().apply { airTicks = 6; positionGapTicks = 1 }
        val environment = ground().copy(colliding = true, supportingCollision = false)
        val evaluation = evaluator.evaluate(movement(Vec3(0.0, 1.0, 0.0), false), Vec3(0.0, 0.2, 0.0), environment, profile, state)

        assertTrue(evaluation.failed("movement.spider.a"))
    }

    @Test
    fun `fake ground on liquid triggers jesus`() {
        val state = PlayerState().apply { airTicks = 6; positionGapTicks = 1 }
        val liquid = ground().copy(
            supportingCollision = false,
            collisionBoxes = emptyList(),
            exemptEnvironment = true,
            environmentTags = setOf("liquid")
        )
        val evaluation = evaluator.evaluate(movement(Vec3(0.0, 1.0, 0.0), true), Vec3(0.1, 0.0, 0.0), liquid, profile, state)

        assertTrue(evaluation.failed("movement.jesus.a"))
    }

    @Test
    fun `claimed ground takeoff after phantom air ticks does not trigger fly`() {
        // A lagging support sample can accumulate phantom air ticks while the player runs on solid
        // ground; the next normal jump must still be recognised as a takeoff via the client's
        // onGround claim instead of fly-flagging a 0.42 offset.
        val state = PlayerState().apply {
            airTicks = 5
            lastVerticalDelta = 0.0
            lastMovement = movement(Vec3(0.0, 0.0, 0.0), onGround = true)
            positionGapTicks = 1
        }
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        val evaluation = evaluator.evaluate(movement(Vec3(0.0, 0.42, 0.0), false), Vec3(0.0, 0.42, 0.0), air, profile, state)

        assertFalse(evaluation.failed("movement.fly.a"))
    }

    @Test
    fun `claimed ground sprint rejump after phantom air ticks does not trigger speed`() {
        val state = PlayerState().apply {
            sprinting = true
            airTicks = 3
            predictedHorizontal = 0.153
            lastHorizontalDelta = 0.41
            lastMovement = movement(Vec3(0.0, 0.0, 0.0), onGround = true)
            positionGapTicks = 1
        }
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        val delta = Vec3(0.0, 0.42, 0.6)
        val evaluation = evaluator.evaluate(movement(delta, onGround = false), delta, air, profile, state)

        assertFalse(evaluation.failed("movement.speed.a"))
    }

    @Test
    fun `baseline seeds the horizontal predictor from observed momentum`() {
        // Post-exemption transitions (knockback, elytra landing) carry real momentum; a zeroed
        // predictor would speed-flag the first evaluated frame.
        val state = PlayerState()
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        evaluator.baseline(Vec3(0.9, -0.2, 0.0), air, state)

        assertTrue(state.predictedHorizontal > 0.7)
    }

    @Test
    fun `a sliver of feet box overlap still counts as support`() {
        // Ground platform ends at x=2.0; player centre at 2.28 leaves ~0.019 overlap — vanilla keeps
        // this player grounded, so supports() must too (edge-standing hover/nofall FP regression).
        assertTrue(evaluator.supports(Vec3(2.28, 0.0, 0.0), ground()))
    }

    @Test
    fun `jump boost three takeoff does not trigger step`() {
        val state = PlayerState().apply { positionGapTicks = 1 }
        val boosted = ground().copy(jumpAmplifier = 2)
        val evaluation = evaluator.evaluate(movement(Vec3(0.0, 0.8, 0.0), false), Vec3(0.0, 0.8, 0.0), boosted, profile, state)

        assertFalse(evaluation.failed("movement.step.a"))
    }

    @Test
    fun `oversized instant step triggers step`() {
        val state = PlayerState().apply { positionGapTicks = 1 }
        val evaluation = evaluator.evaluate(movement(Vec3(0.0, 0.8, 0.0), false), Vec3(0.0, 0.8, 0.0), ground(), profile, state)

        assertTrue(evaluation.failed("movement.step.a"))
    }

    @Test
    fun `entering a solid body collision triggers phase`() {
        val previous = movement(Vec3(-0.7, 0.0, 0.0), true)
        val state = PlayerState().apply { lastMovement = previous; positionGapTicks = 1 }
        val wall = Box(-0.1, 0.0, -1.0, 0.1, 2.0, 1.0)
        val environment = ground().copy(collisionBoxes = ground().collisionBoxes + wall)
        val current = movement(Vec3(0.0, 0.0, 0.0), true)
        val evaluation = evaluator.evaluate(current, Vec3(0.7, 0.0, 0.0), environment, profile, state)

        assertTrue(evaluation.failed("movement.phase.a"))
    }

    private fun MovementEvaluation.failed(checkId: String) = failures.any { it.checkId == checkId }

    private fun movement(position: Vec3, onGround: Boolean) = MovementFrame(
        UUID(0, 1), 1, 1, "V_1_21_11", true, false, position, 0f, 0f, onGround
    )

    private fun ground() = EnvironmentFrame(
        capturedAt = 1,
        tick = 1,
        worldId = UUID(0, 2),
        position = Vec3(0.0, 0.0, 0.0),
        yaw = 0f,
        pitch = 0f,
        entityBox = Box(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3),
        collisionBoxes = listOf(Box(-2.0, -1.0, -2.0, 2.0, 0.0, 2.0)),
        supportingCollision = true,
        colliding = false,
        gameMode = "SURVIVAL",
        surface = "GRASS_BLOCK",
        surfaceSlipperiness = 0.6,
        walkSpeed = 0.2f,
        movementSpeed = 0.1,
        jumpStrength = 0.42,
        gravity = 0.08,
        stepHeight = 0.6,
        eyeHeight = 1.62,
        speedAmplifier = -1,
        jumpAmplifier = -1,
        ping = 0,
        tps = 20.0,
        velocity = Vec3(0.0, 0.0, 0.0),
        exemptEnvironment = false,
        environmentTags = emptySet(),
        chunkLoaded = true
    )
}
