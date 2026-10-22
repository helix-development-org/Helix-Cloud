package de.tytoss.iguard.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IGuardConfigTest {
    @Test
    fun `defaults mirror the bundled per-check tuning with per-second decay`() {
        val config = IGuardConfig.defaults()
        val fly = config.dynamic.checks.getValue("movement.fly.a")

        assertEquals(CheckConfig(true, 4.0, 7.0, 4.0), fly)
        assertEquals(0.0, config.dynamic.checks.getValue("inventory.impossible.a").decayPerSecond)
        assertEquals(IGuardConfig.checkIds, config.dynamic.checks.keys)
        assertEquals(32, IGuardConfig.checkIds.size)
        assertEquals(DEFAULT_DEBUG_RATE_LIMIT_MILLIS, config.dynamic.debug.rateLimitMillis)
        assertEquals(TraceConfig(DEFAULT_TRACE_MAX_BYTES, DEFAULT_TRACE_MAX_SECONDS, DEFAULT_TRACE_QUEUE), config.trace)
    }

    @Test
    fun `values are read from the source and clamped`() {
        val source = MapConfigSource(
            mapOf(
                "checks.movement.speed.a.decay-per-second" to "1.5",
                "checks.movement.speed.a.alert-vl" to 7,
                "checks.movement.speed.a.enabled" to "false",
                "debug.rate-limit-millis" to 999_999,
                "trace.max-seconds" to 5,
                "sanctions.mode" to "ENFORCE",
            ),
        )
        val config = IGuardConfig.parse(source, "lobby-1")
        val speed = config.dynamic.checks.getValue("movement.speed.a")

        assertEquals(CheckConfig(false, 7.0, 9.0, 1.5), speed)
        assertEquals(60_000, config.dynamic.debug.rateLimitMillis)
        assertEquals(10, config.trace.maxSeconds)
        assertEquals("enforce", config.sanctions.mode)
        assertEquals("lobby-1", config.serverId)
        assertTrue(config.dynamic.confidence.signal.getValue("movement.speed.a") > 0.0)
    }
}
