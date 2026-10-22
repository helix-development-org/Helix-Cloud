package org.helix.cinematic

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CinematicMathTest {
    private fun near(a: Double, b: Double, eps: Double = 1e-6) = assertTrue(abs(a - b) < eps, "$a != $b")

    @Test
    fun `look at uses minecraft yaw and pitch`() {
        val south = Rotation.lookingAt(Vec3.ZERO, Vec3(0.0, 0.0, 5.0))
        near(south.yaw.toDouble(), 0.0)
        val west = Rotation.lookingAt(Vec3.ZERO, Vec3(-5.0, 0.0, 0.0))
        near(west.yaw.toDouble(), 90.0, 1e-4)
        val down = Rotation.lookingAt(Vec3(0.0, 5.0, 0.0), Vec3(0.0, 0.0, 5.0))
        near(down.pitch.toDouble(), 45.0, 1e-4)
    }

    @Test
    fun `rotation lerp takes the short way over 180`() {
        val r = Rotation.lerp(Rotation(170f, 0f), Rotation(-170f, 0f), 0.5)
        assertTrue(abs(abs(r.yaw) - 180f) < 1e-3, "${r.yaw}")
    }

    @Test
    fun `orbit starts at its angle and radius and ends closer`() {
        val path = CameraPath.orbit(Target.at(Vec3.ZERO), startAngle = 0.0, sweep = 90.0, radius = 10.0..4.0, height = 2.0..1.0)
        val start = assertNotNull(path.position(0.0))
        near(start.x, 0.0); near(start.z, 10.0); near(start.y, 2.0)
        val end = assertNotNull(path.position(1.0))
        near(end.x, -4.0); near(end.z, 0.0, 1e-9); near(end.y, 1.0)
    }

    @Test
    fun `spline passes through its waypoints`() {
        val a = Vec3(0.0, 0.0, 0.0); val b = Vec3(10.0, 5.0, 0.0); val c = Vec3(20.0, 0.0, 10.0)
        val path = CameraPath.spline(a, b, c)
        assertEquals(a, path.position(0.0))
        val mid = assertNotNull(path.position(0.5))
        near(mid.x, b.x); near(mid.y, b.y); near(mid.z, b.z)
        val end = assertNotNull(path.position(1.0))
        near(end.x, c.x, 1e-6); near(end.z, c.z, 1e-6)
    }

    @Test
    fun `dolly stops at the given distance and a lost target ends the path`() {
        val path = CameraPath.dolly(Vec3(10.0, 0.0, 0.0), Target.at(Vec3.ZERO), stopDistance = 3.0)
        near(assertNotNull(path.position(1.0)).x, 3.0)
        assertNull(CameraPath.orbit(Target { null }, 0.0, 10.0, 5.0..5.0, 1.0..1.0).position(0.5))
    }

    @Test
    fun `easings keep their ends`() {
        listOf(Easing.LINEAR, Easing.SMOOTH, Easing.IN, Easing.OUT, Easing.IN_OUT, Easing.SINE).forEach {
            near(it.ease(0.0), 0.0, 1e-9)
            near(it.ease(1.0), 1.0, 1e-9)
        }
    }

    @Test
    fun `scenes play fixed shots, loop or generate`() {
        val fixed = scene("s") {
            shot(20) { path = CameraPath.static(Vec3.ZERO) }
            shot(40) { path = CameraPath.static(Vec3.ZERO) }
        }
        assertEquals(40, fixed.shot(1)?.ticks)
        assertNull(fixed.shot(2))
        val looped = scene("l") { loop = true; shot(20) { path = CameraPath.static(Vec3.ZERO) } }
        assertEquals(20, looped.shot(7)?.ticks)
        val generated = scene("g") { generate { i -> if (i < 3) shot(10 + i) { path = CameraPath.static(Vec3.ZERO) } else null } }
        assertEquals(12, generated.shot(2)?.ticks)
        assertNull(generated.shot(3))
    }

    @Test
    fun `fade covers the screen before the cut`() {
        assertEquals(10, Transition.Fade(inTicks = 8, holdTicks = 4).coverTicks)
        assertEquals(0, Transition.Cut.coverTicks)
    }
}
