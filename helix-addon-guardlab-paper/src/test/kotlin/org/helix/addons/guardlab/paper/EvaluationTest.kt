package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvaluationTest {
    private fun scenario(category: Category, expect: Expectation, lag: String = "none", covers: List<String> = emptyList()) = Scenario(
        id = "s",
        title = "S",
        category = category,
        setup = ScenarioSetup("flat.start"),
        script = JsonArray(emptyList()),
        dummyScript = JsonArray(emptyList()),
        cheat = JsonObject(mapOf("mode" to JsonPrimitive(if (category == Category.CHEAT) "speed" else "none"))),
        setupTicks = 60,
        durationTicks = 100,
        lag = lag,
        expect = expect,
        covers = covers,
    )

    private val legit = scenario(Category.LEGIT, Expectation(none = true))
    private val speedCheat = scenario(Category.CHEAT, Expectation(checks = listOf("movement.speed.a", "movement.timer.a"), minVl = 2.0))
    private val alert = { _: String -> 5.0 }

    private fun observed(vararg checks: CheckObservation, setbacks: Int = 0, avgPing: Double = 0.0) =
        ScenarioObservation(checks = checks.toList(), setbacks = setbacks, clientFinished = true, clientTicks = 100, avgPing = avgPing)

    @Test
    fun `tracker sums rises only and survives decay`() {
        val tracker = ViolationTracker()
        tracker.start(mapOf("movement.speed.a" to 3.0))
        tracker.sample(mapOf("movement.speed.a" to 2.5)) // decay
        assertEquals(mapOf("movement.speed.a" to 1.5), tracker.sample(mapOf("movement.speed.a" to 4.0)))
        tracker.sample(emptyMap()) // decayed away entirely
        tracker.sample(mapOf("movement.speed.a" to 1.0, "movement.fly.a" to 2.0))
        val speed = tracker.observations().first { it.check == "movement.speed.a" }
        assertEquals(2, speed.flags)
        assertEquals(2.5, speed.total, 1e-9)
        assertEquals(1.0, speed.peak, 1e-9) // 4.0 - baseline 3.0
        assertEquals(listOf("movement.fly.a", "movement.speed.a"), tracker.observations().map { it.check })
        tracker.start(emptyMap())
        assertTrue(tracker.observations().isEmpty())
    }

    @Test
    fun `legit passes clean, warns on noise and fails on alerts or setbacks`() {
        assertEquals(Status.PASS, Evaluator.judge(legit, observed(), alert).status)
        val noise = Evaluator.judge(legit, observed(CheckObservation("movement.speed.a", 2, 1.2, 1.2)), alert)
        assertEquals(Status.WARN, noise.status)
        assertTrue(noise.reasons.single().startsWith("Rauschen movement.speed.a"))
        val loud = Evaluator.judge(legit, observed(CheckObservation("movement.fly.a", 6, 7.0, 5.5)), alert)
        assertEquals(Status.FAIL, loud.status)
        assertTrue(loud.reasons.single().startsWith("Fehlalarm movement.fly.a"))
        val setback = Evaluator.judge(legit, observed(setbacks = 2), alert)
        assertEquals(Status.FAIL, setback.status)
        assertTrue(setback.reasons.single().contains("Setback"))
    }

    @Test
    fun `cheat needs an expected check at the minimum level`() {
        val detected = Evaluator.judge(speedCheat, observed(CheckObservation("movement.timer.a", 3, 2.4, 2.4)), alert)
        assertEquals(Status.PASS, detected.status)
        assertTrue(detected.reasons.first().startsWith("erkannt: movement.timer.a"))
        val tooLow = Evaluator.judge(speedCheat, observed(CheckObservation("movement.speed.a", 1, 1.0, 1.0)), alert)
        assertEquals(Status.FAIL, tooLow.status)
        val other = Evaluator.judge(
            speedCheat,
            observed(CheckObservation("movement.speed.a", 4, 4.0, 4.0), CheckObservation("movement.fly.a", 1, 0.5, 0.5)),
            alert,
        )
        assertEquals(Status.PASS, other.status)
        assertTrue(other.reasons.any { it.startsWith("außerdem gemeldet: movement.fly.a") })
    }

    @Test
    fun `all-of and setback expectations are enforced`() {
        val both = scenario(Category.CHEAT, Expectation(all = listOf("movement.fly.a", "movement.nofall.a"), setback = true))
        val onlyFly = observed(CheckObservation("movement.fly.a", 3, 3.0, 3.0), setbacks = 1)
        assertEquals(Status.FAIL, Evaluator.judge(both, onlyFly, alert).status)
        val all = observed(CheckObservation("movement.fly.a", 3, 3.0, 3.0), CheckObservation("movement.nofall.a", 1, 1.0, 1.0))
        val noSetback = Evaluator.judge(both, all, alert)
        assertEquals(Status.FAIL, noSetback.status)
        assertTrue("kein Setback, obwohl erwartet" in noSetback.reasons)
        assertEquals(Status.PASS, Evaluator.judge(both, all.copy(setbacks = 1), alert).status)
        val forbidden = scenario(Category.CHEAT, Expectation(checks = listOf("movement.fly.a"), setback = false))
        assertEquals(Status.FAIL, Evaluator.judge(forbidden, onlyFly, alert).status)
    }

    @Test
    fun `skips, client errors and timeouts`() {
        assertEquals(Status.SKIP, Evaluator.judge(speedCheat, ScenarioObservation(skipped = "kein Dummy"), alert).status)
        assertEquals(Status.SKIP, Evaluator.judge(speedCheat, ScenarioObservation(error = "unsupported cheat mode nuker"), alert).status)
        assertEquals(Status.FAIL, Evaluator.judge(speedCheat, ScenarioObservation(error = "boom"), alert).status)
        val timeout = Evaluator.judge(legit, ScenarioObservation(clientFinished = false), alert)
        assertEquals(Status.FAIL, timeout.status)
        assertTrue(timeout.reasons.single().contains("Timeout"))
    }

    @Test
    fun `lag that never reached the client turns a pass into a warning`() {
        val laggy = scenario(Category.LAG, Expectation(none = true), lag = "ping250_jitter50")
        assertEquals(Status.WARN, Evaluator.judge(laggy, observed(avgPing = 20.0), alert).status)
        assertEquals(Status.PASS, Evaluator.judge(laggy, observed(avgPing = 240.0), alert).status)
        val spike = scenario(Category.LAG, Expectation(none = true), lag = "spike")
        assertEquals(Status.PASS, Evaluator.judge(spike, observed(avgPing = 10.0), alert).status)
    }

    @Test
    fun `runner notes are appended`() {
        val verdict = Evaluator.judge(legit, observed().copy(notes = listOf("Spieler ist gestorben")), alert)
        assertEquals(Status.PASS, verdict.status)
        assertEquals(listOf("keine Meldung", "Spieler ist gestorben"), verdict.reasons)
    }

    private fun frames(all: Double = 0.0, movement: Double? = 0.0, exemptions: List<Share> = emptyList()) = FrameSummary(
        frames = 400, ticks = 200, movementTicks = if (movement == null) 0 else 200, allExemptShare = all,
        movementExemptShare = movement, exemptions = exemptions, tags = emptyList(), checks = emptyList(),
    )

    @Test
    fun `legit pass without real checking is marked exempt`() {
        val boat = scenario(Category.LEGIT, Expectation(none = true), covers = listOf("movement.speed.a"))
        val riding = Evaluator.judge(boat, observed().copy(frames = frames(movement = 0.96, exemptions = listOf(Share("vehicle (MOVEMENT)", 192, 0.96)))), alert)
        assertEquals(Status.PASS, riding.status)
        assertTrue(riding.exempt)
        assertTrue(riding.reasons.single().contains("Bewegungs-Checks ausgesetzt in 96 %"), riding.reasons.toString())
        assertTrue(riding.reasons.single().contains("exempt: vehicle (MOVEMENT) 96 %"))
        val seated = Evaluator.judge(boat, observed().copy(frames = frames(movement = null)), alert)
        assertTrue(seated.exempt)
        assertTrue(seated.reasons.single().contains("keine Bewegungs-Frames"))
        val checked = Evaluator.judge(boat, observed().copy(frames = frames(movement = 0.5)), alert)
        assertFalse(checked.exempt)
        assertEquals(listOf("keine Meldung"), checked.reasons)
    }

    @Test
    fun `movement exemptions do not matter for combat scenarios but all-scope ones do`() {
        val combat = scenario(Category.LEGIT, Expectation(none = true), covers = listOf("combat.reach.a"))
        assertFalse(Evaluator.judge(combat, observed().copy(frames = frames(movement = 1.0)), alert).exempt)
        val cutscene = Evaluator.judge(combat, observed().copy(frames = frames(all = 0.9)), alert)
        assertTrue(cutscene.exempt)
        assertTrue(cutscene.reasons.single().contains("alle Checks ausgesetzt in 90 %"))
    }

    @Test
    fun `exempt marking leaves failures, warnings and cheats alone`() {
        val boat = scenario(Category.LEGIT, Expectation(none = true), covers = listOf("movement.speed.a"))
        val noisy = observed(CheckObservation("movement.speed.a", 1, 0.5, 0.5)).copy(frames = frames(movement = 0.9))
        val warn = Evaluator.judge(boat, noisy, alert)
        assertEquals(Status.WARN, warn.status)
        assertFalse(warn.exempt)
        val cheat = Evaluator.judge(speedCheat, observed(CheckObservation("movement.speed.a", 3, 3.0, 3.0)).copy(frames = frames(all = 1.0)), alert)
        assertFalse(cheat.exempt)
        val noFrames = Evaluator.judge(boat, observed().copy(frames = frames(movement = 1.0).copy(frames = 0)), alert)
        assertFalse(noFrames.exempt)
    }

    @Test
    fun `alert levels from frame reports win over the config`() {
        val withAlert = FrameSummary(
            frames = 10, ticks = 10, movementTicks = 10, allExemptShare = 0.0, movementExemptShare = 0.0, exemptions = emptyList(), tags = emptyList(),
            checks = listOf(CheckActivity("movement.speed.a", 10, 2, 2, 3.0, 3.0, 2.0)),
        )
        val verdict = Evaluator.judge(legit, observed(CheckObservation("movement.speed.a", 2, 3.0, 3.0)).copy(frames = withAlert), alert)
        assertEquals(Status.FAIL, verdict.status) // the config's 5.0 would only have made it noise
        assertTrue(verdict.reasons.first().startsWith("Fehlalarm movement.speed.a"))
    }
}
