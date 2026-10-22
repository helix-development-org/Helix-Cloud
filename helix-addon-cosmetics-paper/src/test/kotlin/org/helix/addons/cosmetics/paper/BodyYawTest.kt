package org.helix.addons.cosmetics.paper

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyYawTest {
    @Test
    fun `standing still the body stays until the head turns past the limit`() {
        val body = BodyYaw(0f)
        body.update(0f, 0.0, 0.0)
        assertEquals(0f, body.update(30f, 0.0, 0.0))
        assertEquals(45f, body.update(90f, 0.0, 0.0))
        assertEquals(45f, body.update(60f, 0.0, 0.0), "turning back within the limit keeps the body")
    }

    @Test
    fun `walking the body catches up with the head`() {
        val body = BodyYaw(0f)
        body.update(90f, 0.0, 0.0)
        var yaw = 0f
        for (i in 1..20) yaw = body.update(90f, 0.0, i * 0.2)
        assertTrue(abs(yaw - 90f) < 1f, "body faces the head after walking: $yaw")
    }

    @Test
    fun `turning across the back wraps instead of spinning round`() {
        val body = BodyYaw(170f)
        body.update(170f, 0.0, 0.0)
        val yaw = body.update(-170f, 0.0, 0.5)
        assertTrue(yaw > 170f || yaw < -170f, "shortest way over ±180: $yaw")
    }
}
