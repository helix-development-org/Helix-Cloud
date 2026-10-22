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
            state,
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
            state,
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
            state,
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
            state,
        )

        assertTrue(evaluation.failed("movement.speed.a"))
    }

    @Test
    fun `sustained speed cheat flags every tick and its weight scales with the offset`() {
        // airborne for a while: the tick starts in the air (air acceleration)
        val state = PlayerState().apply {
            sprinting = false
            predictedHorizontal = 0.08
            positionGapTicks = 1
            airTicks = 3
        }

        // a small offset over the limit accrues little weight...
        val smallDelta = Vec3(0.0, 0.0, 0.14)
        val small = evaluator.evaluate(movement(Vec3(0.0, 1.0, 0.14), onGround = false), smallDelta, ground(), profile, state)
        val smallWeight = small.failures.firstOrNull { it.checkId == "movement.speed.a" }?.weight ?: 0.0

        // ...while a blatant offset accrues far more, on the same clean state
        val bigState = PlayerState().apply { predictedHorizontal = 0.08; positionGapTicks = 1; airTicks = 3 }
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
            state,
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
            environmentTags = setOf("liquid"),
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

    @Test
    fun `a brief non-descending glide burst does not trigger elytra-fly`() {
        val state = PlayerState()

        // A single rocket-boost burst: several non-descending ticks, well short of the sustained
        // no-gravity streak the check requires.
        repeat(10) {
            assertEquals(null, evaluator.elytraFailure(Vec3(0.0, 0.05, 1.0), state))
        }
    }

    @Test
    fun `sustained non-descending glide triggers elytra-fly`() {
        val state = PlayerState()
        var failure: CheckFailure? = null

        repeat(150) {
            failure = evaluator.elytraFailure(Vec3(0.0, 0.02, 1.0), state)
        }

        assertEquals("movement.elytrafly.a", failure?.checkId)
    }

    @Test
    fun `a real descending tick resets the elytra-fly streak`() {
        val state = PlayerState()
        repeat(90) { evaluator.elytraFailure(Vec3(0.0, 0.02, 1.0), state) }

        // Vanilla gravity reasserting itself for one tick must clear the streak entirely, not just
        // pause it — a hacked client alternating one real tick per 99 fake ones must not slip through.
        evaluator.elytraFailure(Vec3(0.0, -0.1, 1.0), state)
        assertEquals(0, state.elytraLevelStreak)

        repeat(90) { assertEquals(null, evaluator.elytraFailure(Vec3(0.0, 0.02, 1.0), state)) }
    }

    @Test
    fun `walking off an edge keeps ground acceleration for the ticks that started on ground`() {
        // GuardLab legit_fall_damage: the move that crosses the edge ends unsupported (and the next one already
        // falls), yet both ticks started on the ground in vanilla and accelerate like on ground.
        val state = PlayerState().apply { predictedHorizontal = 0.2203 * 0.546; positionGapTicks = 1 }
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        val edge = Vec3(0.0, 0.0, 0.2154)
        state.lastMovement = movement(Vec3(0.0, 0.0, 0.0), onGround = true)
        val first = evaluator.evaluate(movement(edge, onGround = true), edge, air, profile, state)
        assertFalse(first.failed("movement.speed.a"), "edge tick")
        evaluator.acceptHorizontal(edge, air, state, first.supporting)
        state.airTicks = 1
        state.lastMovement = movement(edge, onGround = true)
        state.positionGapTicks = 1
        val fall = Vec3(0.0, -0.0784, 0.2156)
        val second = evaluator.evaluate(movement(edge + fall, onGround = false), fall, air, profile, state)
        assertFalse(second.failed("movement.speed.a"), "first falling tick")
    }

    @Test
    fun `a walking jump keeps ground acceleration on the takeoff tick`() {
        val state = PlayerState().apply {
            predictedHorizontal = 0.2203 * 0.546
            positionGapTicks = 1
            lastMovement = movement(Vec3(0.0, 0.0, 0.0), onGround = true)
        }
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        val takeoff = Vec3(0.0, 0.42, 0.2449)
        val evaluation = evaluator.evaluate(movement(takeoff, onGround = false), takeoff, air, profile, state)

        assertFalse(evaluation.failed("movement.speed.a"))
    }

    @Test
    fun `a sustained small speed boost flags per window while vanilla sprinting stays clean`() {
        val state = PlayerState().apply { sprinting = true }
        var velocity = 0.0

        fun run(frames: Int, factor: Double): List<CheckFailure> = buildList {
            repeat(frames) {
                velocity = velocity * 0.546 + 0.13
                val delta = Vec3(0.0, 0.0, velocity * factor)
                state.positionGapTicks = 1
                // the fixture's ground box is small: judge every move at its centre so it stays supported
                val frame = movement(Vec3(0.0, 0.0, 0.0), onGround = true)
                val evaluation = evaluator.evaluate(frame, delta, ground(), profile, state)
                addAll(evaluation.failures.filter { it.checkId == "movement.speed.a" })
                if (evaluation.failed("movement.speed.a")) {
                    evaluator.rejectHorizontal(ground(), state, evaluation.supporting)
                } else {
                    evaluator.acceptHorizontal(delta, ground(), state, evaluation.supporting)
                }
                state.lastMovement = frame
            }
        }

        assertTrue(run(60, 1.0).isEmpty(), "vanilla sprint must stay clean")
        val boosted = run(60, 1.15)
        assertTrue(boosted.isNotEmpty(), "a 15 % boost must flag")
        assertTrue(boosted.all { it.evidence["mode"] == "sustained" }, "it stays inside the per-tick tolerance")
        assertTrue(boosted.size <= 3, "one verdict per window, not per tick")
    }

    @Test
    fun `the second tick of a slime bounce is no step`() {
        val state = PlayerState().apply { positionGapTicks = 1; lastVerticalDelta = 1.038 }
        val air = ground().copy(supportingCollision = false, collisionBoxes = emptyList(), surface = "AIR")
        val rise = Vec3(0.0, 0.9391, 0.0336)
        val evaluation = evaluator.evaluate(movement(rise, onGround = false), rise, air, profile, state)

        assertFalse(evaluation.failed("movement.step.a"))
    }

    private fun MovementEvaluation.failed(checkId: String) = failures.any { it.checkId == checkId }

    private fun movement(position: Vec3, onGround: Boolean) = MovementFrame(
        UUID(0, 1), 1, 1, "V_1_21_11", true, false, position, 0f, 0f, onGround,
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
        chunkLoaded = true,
    )
}
