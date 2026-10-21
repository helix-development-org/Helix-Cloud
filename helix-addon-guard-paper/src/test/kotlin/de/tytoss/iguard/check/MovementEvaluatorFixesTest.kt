package de.tytoss.iguard.check

import de.tytoss.iguard.PLAYER
import de.tytoss.iguard.environment
import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.Vec3
import de.tytoss.iguard.profile.VersionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the evaluator fixes (Jesus surface, low-TPS context, fast-ladder, high-jump). */
class MovementEvaluatorFixesTest {
    private val evaluator = MovementEvaluator()
    private val profile = VersionProfile(0.08, 0.98, 0.42, 0.29, 0.36, 1.3, 3.0)

    // --- Jesus on the liquid surface (feet in air above water) ---

    @Test
    fun `standing on the water surface with a ground claim flags jesus`() {
        val state = PlayerState().apply { airTicks = 6; positionGapTicks = 1 }
        val surface = environment(1, Vec3(0.5, 63.0, 0.5), setOf("liquid-surface"), boxes = emptyList())
        val evaluation = evaluator.evaluate(frame(Vec3(0.7, 63.0, 0.5), onGround = true), Vec3(0.2, 0.0, 0.0), surface, profile, state)

        val failure = evaluation.failures.single { it.checkId == "movement.jesus.a" }
        assertEquals(true, failure.evidence["surface"])
    }

    @Test
    fun `hovering level above water flags jesus without a ground claim`() {
        val state = PlayerState().apply { airTicks = 8; positionGapTicks = 1 }
        val surface = environment(1, Vec3(0.5, 63.0, 0.5), setOf("liquid-surface"), boxes = emptyList())
        var flagged = false
        repeat(8) {
            val evaluation = evaluator.evaluate(frame(Vec3(0.5, 63.0, 0.5), onGround = false), Vec3(0.15, 0.0, 0.0), surface, profile, state)
            flagged = flagged || evaluation.failures.any { it.checkId == "movement.jesus.a" }
        }

        assertTrue(flagged)
    }

    @Test
    fun `a jump arc across water never flags jesus`() {
        val state = PlayerState().apply { airTicks = 0; positionGapTicks = 1 }
        val surface = environment(1, Vec3(0.5, 63.0, 0.5), setOf("liquid-surface"), boxes = emptyList())
        var vertical = 0.42
        repeat(12) {
            val evaluation = evaluator.evaluate(frame(Vec3(0.5, 64.0, 0.5), onGround = false), Vec3(0.3, vertical, 0.0), surface, profile, state)
            assertFalse(evaluation.failures.any { it.checkId == "movement.jesus.a" }, "dy=$vertical")
            state.lastVerticalDelta = vertical
            state.airTicks++
            vertical = (vertical - 0.08) * 0.98
        }
    }

    @Test
    fun `a lily pad or block above water supports and never flags`() {
        val state = PlayerState().apply { airTicks = 6; positionGapTicks = 1 }
        val lilyPad = environment(1, Vec3(0.5, 63.0, 0.5), setOf("liquid-surface"), boxes = listOf(Box(0.0, 62.0, 0.0, 1.0, 63.015625, 1.0)))
        val evaluation = evaluator.evaluate(frame(Vec3(0.5, 63.015625, 0.5), onGround = true), Vec3(0.0, 0.0, 0.0), lilyPad, profile, state)

        assertTrue(evaluation.failures.none { it.checkId == "movement.jesus.a" })
    }

    // --- low TPS / liquid surface are context tags, not exemptions ---

    @Test
    fun `phase still runs with low tps but enters deeper before flagging`() {
        val wall = Box(1.0, 0.0, -1.0, 2.0, 2.0, 1.0)
        val base = environment(1, Vec3(0.0, 0.0, 0.0), setOf("low-tps"), boxes = listOf(Box(-2.0, -1.0, -2.0, 3.0, 0.0, 2.0), wall))
        val state = PlayerState().apply { lastMovement = frame(Vec3(0.5, 0.0, 0.0), true); positionGapTicks = 1 }

        // deep entry: flagged even when lagging
        val deep = evaluator.evaluate(frame(Vec3(0.9, 0.0, 0.0), true), Vec3(0.4, 0.0, 0.0), base, profile, state, laggy = true)
        assertTrue(deep.failures.any { it.checkId == "movement.phase.a" })

        // a 5 cm graze only counts on a healthy server
        val graze = Vec3(0.75, 0.0, 0.0)
        assertTrue(evaluator.evaluate(frame(graze, true), Vec3(0.25, 0.0, 0.0), base, profile, state, laggy = false).failures.any { it.checkId == "movement.phase.a" })
        assertFalse(evaluator.evaluate(frame(graze, true), Vec3(0.25, 0.0, 0.0), base, profile, state, laggy = true).failures.any { it.checkId == "movement.phase.a" })
    }

    @Test
    fun `step and spider run with low tps and widen their tolerance`() {
        val air = environment(1, Vec3(0.0, 0.0, 0.0), setOf("low-tps"), boxes = emptyList())
        val stepState = PlayerState().apply { airTicks = 0; positionGapTicks = 1 }
        assertTrue(evaluator.evaluate(frame(Vec3(0.0, 1.0, 0.0), false), Vec3(0.0, 1.0, 0.0), air, profile, stepState, laggy = true).failures.any { it.checkId == "movement.step.a" })
        assertFalse(evaluator.evaluate(frame(Vec3(0.0, 0.77, 0.0), false), Vec3(0.0, 0.77, 0.0), air, profile, stepState, laggy = true).failures.any { it.checkId == "movement.step.a" })

        val wall = environment(1, Vec3(0.0, 2.0, 0.0), setOf("low-tps"), boxes = listOf(Box(0.25, 0.0, -1.0, 1.25, 5.0, 1.0)))
        val climbing = PlayerState().apply { airTicks = 6; positionGapTicks = 1; lastVerticalDelta = 0.3 }
        assertTrue(evaluator.evaluate(frame(Vec3(0.0, 2.3, 0.0), false), Vec3(0.0, 0.3, 0.0), wall, profile, climbing, laggy = false).failures.any { it.checkId == "movement.spider.a" })
        assertFalse(evaluator.evaluate(frame(Vec3(0.0, 2.3, 0.0), false), Vec3(0.0, 0.3, 0.0), wall, profile, climbing, laggy = true).failures.any { it.checkId == "movement.spider.a" })
    }

    @Test
    fun `real physics tags still skip phase`() {
        val wall = Box(1.0, 0.0, -1.0, 2.0, 2.0, 1.0)
        val riptide = environment(1, Vec3(0.0, 0.0, 0.0), setOf("riptide"), boxes = listOf(wall))
        val state = PlayerState().apply { lastMovement = frame(Vec3(0.5, 0.0, 0.0), true); positionGapTicks = 1 }

        assertTrue(evaluator.evaluate(frame(Vec3(0.9, 0.0, 0.0), true), Vec3(0.4, 0.0, 0.0), riptide, profile, state).failures.none { it.checkId == "movement.phase.a" })
    }

    // --- fast-ladder / high-jump ---

    @Test
    fun `fast ladder accepts the vanilla climb, a jump arc and the takeoff`() {
        val ladder = environment(1, Vec3(0.0, 65.0, 0.0), setOf("climbable"), boxes = emptyList())
        val climbing = PlayerState().apply { airTicks = 5; lastVerticalDelta = 0.1176; positionGapTicks = 1 }
        assertNull(evaluator.fastLadderFailure(frame(Vec3(0.0, 65.1176, 0.0), false), Vec3(0.0, 0.1176, 0.0), ladder, climbing))

        val arc = PlayerState().apply { airTicks = 1; lastVerticalDelta = 0.42; positionGapTicks = 1 }
        assertNull(evaluator.fastLadderFailure(frame(Vec3(0.0, 65.3332, 0.0), false), Vec3(0.0, 0.3332, 0.0), ladder, arc))

        val takeoff = PlayerState().apply { airTicks = 0; lastVerticalDelta = 0.0; positionGapTicks = 1 }
        assertNull(evaluator.fastLadderFailure(frame(Vec3(0.0, 65.42, 0.0), false), Vec3(0.0, 0.42, 0.0), ladder, takeoff))

        val twoTicks = PlayerState().apply { airTicks = 5; lastVerticalDelta = 0.1176; positionGapTicks = 2 }
        assertNull(evaluator.fastLadderFailure(frame(Vec3(0.0, 65.2352, 0.0), false), Vec3(0.0, 0.2352, 0.0), ladder, twoTicks))
    }

    @Test
    fun `fast ladder flags a sustained climb above vanilla speed`() {
        val ladder = environment(1, Vec3(0.0, 65.0, 0.0), setOf("climbable"), boxes = emptyList())
        val state = PlayerState().apply { airTicks = 5; lastVerticalDelta = 0.25; positionGapTicks = 1 }
        val limits = linkedMapOf<String, Double>()
        val failure = assertNotNull(evaluator.fastLadderFailure(frame(Vec3(0.0, 65.25, 0.0), false), Vec3(0.0, 0.25, 0.0), ladder, state, limits))

        assertEquals("movement.fastladder.a", failure.checkId)
        assertTrue(limits.getValue("climbCeiling") < 0.25)
    }

    @Test
    fun `high jump allows two-tick packets and jump boost`() {
        val ground = environment(1, Vec3(0.0, 64.0, 0.0))
        val state = PlayerState().apply { airTicks = 0; lastVerticalDelta = 0.0; positionGapTicks = 2 }
        // 0.42 + 0.3332: a normal jump whose packet carries two client ticks
        assertNull(evaluator.highJumpFailure(Vec3(0.0, 0.7532, 0.0), ground, state, supportReliable = true))

        val boosted = ground.copy(jumpAmplifier = 1)
        val single = PlayerState().apply { airTicks = 0; lastVerticalDelta = 0.0; positionGapTicks = 1 }
        assertNull(evaluator.highJumpFailure(Vec3(0.0, 0.62, 0.0), boosted, single, supportReliable = true))
        assertNotNull(evaluator.highJumpFailure(Vec3(0.0, 1.0, 0.0), ground, single, supportReliable = true))
    }

    private fun frame(position: Vec3, onGround: Boolean) = MovementFrame(PLAYER, 1, 1, "V_1_21_11", true, false, position, 0f, 0f, onGround)
}
