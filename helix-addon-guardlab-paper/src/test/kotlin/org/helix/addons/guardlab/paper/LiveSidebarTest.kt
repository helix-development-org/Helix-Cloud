package org.helix.addons.guardlab.paper

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSidebarTest {
    private val player = UUID.randomUUID()
    private var now = 10_000L
    private val stats = LiveStats { now }

    private fun frame(
        type: String = "movement",
        exemptions: List<String> = emptyList(),
        tags: Set<String> = emptySet(),
        results: List<CheckSample> = emptyList(),
    ) = FrameSample(player, type, 0, exemptions.isNotEmpty(), exemptions, tags, results)

    private fun speed(failed: Boolean, counted: Boolean = failed, weight: Double = 0.0, evidence: Map<String, String> = emptyMap()) =
        CheckSample("movement.speed.a", failed, counted, weight, 6.7, 5.0, evidence)

    private fun input(run: LiveRun? = null, live: LiveStats.View? = null, levels: List<LevelLine> = emptyList(), iguard: Boolean = true) =
        SidebarInput(run, 42, 19.9, live, levels, iguard, now)

    private fun plain(lines: List<String>) = lines.map { it.replace(Regex("<[^>]+>"), "") }

    @Test
    fun `rates are those of the last complete second`() {
        repeat(3) { stats.record(frame()) }
        stats.record(frame(results = listOf(speed(failed = true, weight = 1.2))))
        assertEquals(0, stats.view(player)!!.framesPerSecond)
        now += 1_000
        val view = stats.view(player)!!
        assertEquals(4, view.framesPerSecond)
        assertEquals(1, view.failsPerSecond)
        now += 2_000
        assertEquals(0, stats.view(player)!!.framesPerSecond)
    }

    @Test
    fun `exemptions are readable and go stale without checked frames`() {
        stats.record(frame(exemptions = listOf("timed:movement:velocity", "tag:vehicle")))
        assertEquals(listOf("velocity (MOVEMENT)", "vehicle (MOVEMENT)"), stats.view(player)!!.exemptions)
        stats.record(frame(type = "tick"))
        assertEquals(2, stats.view(player)!!.exemptions.size)
        now += 1_500
        assertTrue(stats.view(player)!!.exemptions.isEmpty())
    }

    @Test
    fun `last flag prefers counted failures and keeps the evidence`() {
        stats.record(
            frame(
                results = listOf(
                    speed(failed = true, counted = false),
                    CheckSample("movement.timer.a", true, true, 0.5, 1.0, 5.0, mapOf("balance" to "51.000")),
                ),
            ),
        )
        val flag = assertNotNull(stats.view(player)!!.lastFlag)
        assertEquals("movement.timer.a", flag.check)
        assertEquals(mapOf("balance" to "51.000"), flag.evidence)
        stats.forget(player)
        assertNull(stats.view(player))
    }

    @Test
    fun `sidebar without run shows the hint and the viewer's live numbers`() {
        stats.record(frame(tags = setOf("liquid", "climbable"), results = listOf(speed(true, weight = 1.25, evidence = mapOf("horizontal" to "0.509", "limit" to "0.361")))))
        now += 1_000
        val lines = SidebarLines.build(
            input(live = stats.view(player), levels = listOf(LevelLine("movement.speed.a", 6.7, 5.0), LevelLine("movement.fly.a", 0.0, 5.0))),
        )
        val text = plain(lines)
        assertEquals("Kein Lauf aktiv", text[0])
        assertTrue(lines[1].endsWith("/guardlab run \\<suite>"), "the hint is escaped, not a MiniMessage tag")
        assertTrue("Ping 42 ms · TPS 19.9" in text)
        assertTrue("Frames 1/s · Fails 1/s" in text)
        assertTrue("Tags climbable, liquid" in text)
        assertTrue("speed.a 6.7/5" in text)
        assertTrue(text.none { it.startsWith("fly.a") }, "decayed levels are not listed")
        assertTrue("Flag speed.a +1.25 1s" in text)
        assertTrue("horizontal=0.509 limit=0.361" in text)
        assertTrue(lines.size <= SidebarLines.MAX_LINES)
    }

    @Test
    fun `sidebar with run shows suite progress, scenario, cheat and phase`() {
        val run = LiveRun(
            suite = "all", client = player, done = 14, total = 97,
            counts = mapOf(Status.PASS to 12, Status.WARN to 2, Status.GAP to 1),
            scenario = "cheat_speed", cheat = "speed", cheatOn = true, lag = "ping100",
            phase = "läuft", playTick = 85, durationTicks = 160, setbacks = 2,
        )
        val levels = (1..5).map { LevelLine("movement.c$it.a", it.toDouble(), 5.0) }
        val lines = SidebarLines.build(input(run = run, levels = levels, live = LiveStats.View(40, 0, listOf("velocity (MOVEMENT)"), emptySet(), null)))
        val text = plain(lines)
        assertEquals("Suite all 14/97 ✔12 ⚠2 ✖0 ◇1", text[0])
        assertEquals("▶ cheat_speed", text[1])
        assertEquals("Cheat speed AN · Lag ping100", text[2])
        assertEquals("Phase läuft 85/160 · SB 2", text[3])
        assertTrue("Exempt velocity (MOVEMENT)" in text)
        assertEquals(listOf("c5.a 5.0/5", "c4.a 4.0/5", "c3.a 3.0/5"), text.filter { it.matches(Regex("c\\d\\.a .*")) })
        assertEquals(SidebarLines.MAX_LINES - 1, lines.size, "full sidebar without the evidence line")
    }

    @Test
    fun `pause, missing IGuard and long values`() {
        val run = LiveRun("legit", player, 3, 40, emptyMap(), null, "none", false, "none", "Pause", 0, 0, 0)
        val text = plain(SidebarLines.build(input(run = run, iguard = false)))
        assertEquals("Pause – Werte klingen ab", text[1])
        assertTrue("IGuard fehlt – keine Frame-Daten" in text)
        assertTrue("VL alle 0" in text)
        assertEquals("abcdefghi…", SidebarLines.cut("abcdefghijklmnop", 10))
        assertEquals("\\<b>", SidebarLines.cut("<b>"))
        assertEquals("speed.a", SidebarLines.shortCheck("movement.speed.a"))
    }
}
