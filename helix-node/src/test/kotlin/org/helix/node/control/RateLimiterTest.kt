package org.helix.node.control

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private var now = 0L

    @Test
    fun `enforces the per-window limit and starts a fresh window after it elapses`() {
        val limiter = RateLimiter(limit = 2, windowMs = 1_000, clock = { now })

        assertTrue(limiter.allow("ip"))
        assertTrue(limiter.allow("ip"))
        assertFalse(limiter.allow("ip"))

        now += 1_000
        assertTrue(limiter.allow("ip"))
    }

    @Test
    fun `expired windows are swept instead of accumulating one entry per client forever`() {
        val limiter = RateLimiter(limit = 5, windowMs = 1_000, clock = { now })
        repeat(2_000) { i -> limiter.allow("client-$i") }

        now += 10_000
        limiter.allow("fresh")

        val tracked = trackedWindowCount(limiter)
        assertTrue(tracked < 1_024, "expired windows were never cleaned up: $tracked entries tracked")
        assertTrue(limiter.allow("fresh")) // still limits correctly after the sweep
    }

    /** The rate limiter intentionally exposes no map internals, so the growth check peeks via reflection. */
    private fun trackedWindowCount(limiter: RateLimiter): Int {
        val field = RateLimiter::class.java.getDeclaredField("windows")
        field.isAccessible = true
        return (field.get(limiter) as Map<*, *>).size
    }
}
