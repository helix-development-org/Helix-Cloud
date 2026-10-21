package de.tytoss.iguard.snapshot

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DigProbeTest {
    private val player = UUID.fromString("7a3f2c10-5b1e-4c8d-9a2b-3c4d5e6f7a80")

    @Test
    fun `a dig keeps the fastest measured speed until its FINISH`() {
        val probe = DigProbe()
        probe.start(player, 1, 64, 2, now = 0)
        assertNull(probe.finish(player, 1, 64, 2), "not measured yet")
        probe.start(player, 1, 64, 2, now = 0)
        val speeds = ArrayDeque(listOf(0.2, 0.5, 0.3))
        repeat(3) { probe.measure(now = 50) { _, _, _, _ -> "minecraft:stone" to speeds.removeFirst() } }
        assertNull(probe.finish(player, 9, 64, 2), "another block")
        val sample = assertNotNull(probe.finish(player, 1, 64, 2))
        assertEquals(0.5, sample.progressPerTick)
        assertEquals(3, sample.samples)
        assertEquals("minecraft:stone", sample.block)
        assertEquals(0, probe.size(), "FINISH ends the dig")
    }

    @Test
    fun `unmeasurable blocks, cancels and stale digs leave nothing behind`() {
        val probe = DigProbe()
        probe.start(player, 0, 64, 0, now = 0)
        probe.measure(now = 50) { _, _, _, _ -> null }
        assertNull(probe.finish(player, 0, 64, 0))
        probe.start(player, 0, 64, 0, now = 0)
        probe.cancel(player)
        assertEquals(0, probe.size())
        probe.start(player, 0, 64, 0, now = 0)
        probe.measure(now = 120_000) { _, _, _, _ -> "minecraft:stone" to 0.1 }
        assertEquals(0, probe.size(), "digs older than a minute are dropped")
    }
}
