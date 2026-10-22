package org.helix.addons.guardlab.paper

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramesTest {
    private val player = UUID.randomUUID()

    private fun frame(
        tick: Long,
        type: String = "movement",
        exempt: Boolean = false,
        exemptions: List<String> = emptyList(),
        tags: Set<String> = emptySet(),
        results: List<CheckSample> = emptyList(),
        playerId: UUID = player,
    ) = FrameSample(playerId, type, tick, exempt, exemptions, tags, results)

    private fun speed(failed: Boolean = false, counted: Boolean = false, weight: Double = 0.0, level: Double = 0.0) =
        CheckSample("movement.speed.a", failed, counted, weight, level, 5.0)

    @Test
    fun `exemption reasons get a readable label with scope`() {
        assertEquals("velocity (MOVEMENT)", ExemptionReasons.label("timed:movement:velocity"))
        assertEquals("cutscene (ALL)", ExemptionReasons.label("timed:all:cutscene"))
        assertEquals("vehicle (MOVEMENT)", ExemptionReasons.label("tag:vehicle"))
        assertEquals("game-mode (ALL)", ExemptionReasons.label("tag:game-mode"))
        assertEquals("stale-snapshot (MOVEMENT)", ExemptionReasons.label("stale-snapshot"))
        assertTrue(ExemptionReasons.isAllScope("tag:dead"))
        assertFalse(ExemptionReasons.isAllScope("timed:movement:teleport"))
    }

    @Test
    fun `stats count failed and counted per check instead of level differences`() {
        val stats = FrameStats(player, mapOf("movement.speed.a" to 1.0))
        stats.record(frame(1, results = listOf(speed())))
        stats.record(frame(2, results = listOf(speed(failed = true, counted = true, weight = 1.5, level = 2.5))))
        stats.record(frame(3, results = listOf(speed(failed = true, level = 2.4)))) // cooldown: failed, not counted
        stats.record(frame(4, results = listOf(speed(failed = true, counted = true, weight = 1.0, level = 3.4))))
        stats.record(frame(5, playerId = UUID.randomUUID(), results = listOf(speed(failed = true, counted = true, weight = 9.0, level = 9.0))))
        val observation = stats.observations().single()
        assertEquals(2, observation.flags)
        assertEquals(3, observation.failed)
        assertEquals(2.5, observation.total, 1e-9)
        assertEquals(2.4, observation.peak, 1e-9) // 3.4 above the baseline 1.0
        assertEquals(3.4, observation.maxVl, 1e-9)
        val activity = stats.summary().activity("movement.speed.a")!!
        assertEquals(4, activity.evaluated)
        assertEquals(5.0, activity.alertVl, 1e-9)
        assertEquals(4L, stats.frameCount)
    }

    @Test
    fun `exempt shares are measured in client ticks`() {
        val stats = FrameStats(player)
        repeat(10) { tick ->
            val riding = tick < 9
            stats.record(
                frame(
                    tick.toLong(),
                    exempt = riding,
                    exemptions = if (riding) listOf("tag:vehicle") else emptyList(),
                    tags = if (riding) setOf("vehicle", "liquid") else setOf("liquid"),
                ),
            )
            stats.record(frame(tick.toLong(), type = "tick")) // tick-end frames do not double-count
        }
        stats.record(frame(3, type = "attack", exempt = true, exemptions = listOf("timed:all:cutscene")))
        val summary = stats.summary()
        assertEquals(21L, summary.frames)
        assertEquals(10, summary.ticks)
        assertEquals(10, summary.movementTicks)
        assertEquals(0.9, summary.movementExemptShare!!, 1e-9)
        assertEquals(0.1, summary.allExemptShare, 1e-9)
        assertEquals(Share("vehicle (MOVEMENT)", 9, 0.9), summary.exemptions.first())
        assertEquals(Share("cutscene (ALL)", 1, 0.1), summary.exemptions[1])
        assertEquals(listOf("liquid", "vehicle"), summary.tags.map { it.name })
        assertEquals(1.0, summary.tags.first().share, 1e-9)
    }

    @Test
    fun `without movement frames the movement share is unknown`() {
        val stats = FrameStats(player)
        stats.record(frame(1, type = "attack", tags = setOf("vehicle")))
        assertNull(stats.summary().movementExemptShare)
        assertTrue(stats.observations().isEmpty())
    }

    @Test
    fun `iguard trace is copied once its end line is written`() {
        val dir = Files.createTempDirectory("guardlab-trace")
        val source = dir.resolve("iguard.jsonl")
        val target = dir.resolve("report/traces/001_x.iguard.jsonl")
        source.writeText("{\"type\":\"header\"}\n{\"type\":\"frame\"}\n")
        assertFalse(IGuardTraceCopy.finished(source))
        var polls = 0
        val complete = IGuardTraceCopy.copy(source, target, 1_000) {
            if (++polls == 3) source.writeText(source.readText() + "{\"type\":\"end\",\"t\":1,\"reason\":\"stopped\"}\n")
        }
        assertTrue(complete)
        assertEquals(3, polls)
        assertTrue(target.readText().endsWith("\"reason\":\"stopped\"}\n"))
        val unfinished = dir.resolve("open.jsonl").also { it.writeText("{\"type\":\"frame\"}\n") }
        assertFalse(IGuardTraceCopy.copy(unfinished, dir.resolve("copy.jsonl"), 300) {})
        assertTrue(Files.exists(dir.resolve("copy.jsonl")), "an unfinished trace is still copied")
        assertFalse(IGuardTraceCopy.copy(dir.resolve("missing.jsonl"), dir.resolve("none.jsonl"), 0) {})
        dir.toFile().deleteRecursively()
    }
}
