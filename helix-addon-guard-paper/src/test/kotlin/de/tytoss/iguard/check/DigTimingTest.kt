package de.tytoss.iguard.check

import de.tytoss.iguard.config.TuningConfig
import de.tytoss.iguard.model.DigSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DigTimingTest {
    private val tuning = TuningConfig()

    @Test
    fun `minimum ticks follow the vanilla progress sum`() {
        assertEquals(5, DigTiming.minimumTicks(8.0 / 1.5 / 30.0), "stone, diamond pickaxe: six ticks of progress")
        assertEquals(1, DigTiming.minimumTicks(0.5))
        assertEquals(1, DigTiming.minimumTicks(0.99))
        assertEquals(9, DigTiming.minimumTicks(0.1f.toDouble()), "float progress summing to 1 after ten ticks")
        assertEquals(149, DigTiming.minimumTicks(1.0 / 150.0), "obsidian by hand")
        assertNull(DigTiming.minimumTicks(1.0), "instant break sends no FINISH")
        assertNull(DigTiming.minimumTicks(0.0))
        assertNull(DigTiming.minimumTicks(Double.NaN))
    }

    @Test
    fun `tick-end clients are judged by ticks, legacy clients by time`() {
        val stone = DigSample(8.0 / 1.5 / 30.0, 3, "minecraft:stone")
        assertNull(DigTiming.judge(stone, ticks = 5, millis = 0, ping = 0, tuning = tuning), "ticks win over a jitter-compressed time")
        assertNull(DigTiming.judge(stone, ticks = 4, millis = 200, ping = 0, tuning = tuning), "4 >= 70 % of 5")
        val evidence = assertNotNull(DigTiming.judge(stone, ticks = 3, millis = 150, ping = 0, tuning = tuning))
        assertEquals("tick-end", evidence["source"])
        assertEquals(3L, evidence["ticks"])
        assertNotNull(DigTiming.judge(stone, ticks = null, millis = 100, ping = 0, tuning = tuning))
        assertNull(DigTiming.judge(stone, ticks = null, millis = 100, ping = 100, tuning = tuning), "a quarter of the ping is allowance")
        assertNull(DigTiming.judge(stone, ticks = null, millis = 200, ping = 0, tuning = tuning))
        assertNull(DigTiming.judge(DigSample(2.0, 1, "minecraft:torch"), ticks = 0, millis = 0, ping = 0, tuning = tuning))
        val strict = tuning.copy(fastbreakMinRatio = 1.0)
        assertNotNull(DigTiming.judge(stone, ticks = 4, millis = 200, ping = 0, tuning = strict), "min-ratio is configurable")
    }
}
