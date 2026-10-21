package de.tytoss.iguard.trace

import de.tytoss.iguard.PLAYER
import de.tytoss.iguard.api.IGuardCheckResult
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.model.SwingFrame
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugRouterTest {
    private val router = DebugRouter(AtomicReference(IGuardConfig.defaults().dynamic))
    private val lines = mutableListOf<DebugLine>()

    init {
        router.output = { lines += it }
    }

    @Test
    fun `failures below the alert level reach the viewer with evidence`() {
        router.set("viewer", PLAYER, null, true)
        router.route(trace(100, IGuardCheckResult("movement.speed.a", true, true, 0.4, 0.4, 5.0, mapOf("offset" to 0.04))))

        val line = lines.single()
        assertEquals("Tester movement.speed.a VL 0.40/5.0 +0.40 | offset=0.04", line.text)
    }

    @Test
    fun `passed checks and other players stay silent`() {
        router.set("viewer", PLAYER, null, true)
        router.route(trace(100, IGuardCheckResult("movement.speed.a", false, false, 0.0, 0.0, 5.0, emptyMap())))

        assertTrue(lines.isEmpty())
        assertFalse(router.watching(java.util.UUID.randomUUID()))
    }

    @Test
    fun `a single check filter and rate limit with skipped count`() {
        router.set("viewer", PLAYER, "movement.fly.a", true)
        val fly = IGuardCheckResult("movement.fly.a", true, false, 0.0, 2.0, 4.0, emptyMap())
        val speed = IGuardCheckResult("movement.speed.a", true, true, 1.0, 1.0, 5.0, emptyMap())
        router.route(trace(1_000, fly, speed))
        router.route(trace(1_050, fly))
        router.route(trace(1_100, fly))
        router.route(trace(1_250, fly))

        assertEquals(listOf("movement.fly.a", "movement.fly.a"), lines.map { it.checkId })
        assertEquals("Tester movement.fly.a VL 2.00/4.0 (held)", lines[0].text)
        assertTrue(lines[1].text.endsWith("(held) (+2)"))
    }

    @Test
    fun `toggling off one check keeps the others and all off clears`() {
        router.set("viewer", PLAYER, "movement.fly.a", true)
        router.set("viewer", PLAYER, "movement.speed.a", true)
        assertEquals(setOf("movement.speed.a"), router.set("viewer", PLAYER, "movement.fly.a", false))
        assertTrue(router.isWatching("viewer", PLAYER, "movement.speed.a"))

        assertEquals(emptySet(), router.set("viewer", PLAYER, null, false))
        assertFalse(router.watching(PLAYER))
    }

    @Test
    fun `viewer quit removes the subscription`() {
        router.set(DebugRouter.CONSOLE, PLAYER, null, true)
        router.removeViewer(DebugRouter.CONSOLE)

        assertFalse(router.watching(PLAYER))
    }

    private fun trace(at: Long, vararg results: IGuardCheckResult) = FrameTrace(
        SwingFrame(PLAYER, at, at, "V_1_21_11"), "Tester", null, 0, false, emptyList(), results.toList(), emptyMap(), null, null,
        emptyMap(), emptyMap(), emptyMap(), emptyList(),
    )
}
