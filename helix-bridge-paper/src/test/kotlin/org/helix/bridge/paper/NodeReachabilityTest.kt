package org.helix.bridge.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeReachabilityTest {
    @Test
    fun `starts up and never having failed`() {
        val reachability = NodeReachability(clock = { 0L })

        assertTrue(reachability.shouldAttempt())
        assertFalse(reachability.isDown())
    }

    @Test
    fun `a failure marks the node down and schedules a backed-off retry`() {
        var now = 1_000L
        val reachability = NodeReachability(clock = { now })

        val downSince = reachability.recordFailure()

        assertEquals(1_000L, downSince)
        assertTrue(reachability.isDown())
        assertFalse(reachability.shouldAttempt())

        now += 5_000L
        assertTrue(reachability.shouldAttempt())
    }

    @Test
    fun `repeated failures back off exponentially up to the cap`() {
        var now = 0L
        val reachability = NodeReachability(clock = { now })

        reachability.recordFailure()
        now += 5_000L
        reachability.recordFailure()
        now += 10_000L
        reachability.recordFailure()

        // third failure schedules a 20s backoff from `now`
        now += 19_000L
        assertFalse(reachability.shouldAttempt())
        now += 1_000L
        assertTrue(reachability.shouldAttempt())
    }

    @Test
    fun `a success clears the down state`() {
        val reachability = NodeReachability(clock = { 0L })
        reachability.recordFailure()

        reachability.recordSuccess()

        assertFalse(reachability.isDown())
        assertTrue(reachability.shouldAttempt())
    }
}
