package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    private val catalog = SuiteParser.parse(
        """
        {"suites": {"t": ["@legit", "@cheat"]},
         "scenarios": [
           {"id": "walk", "category": "legit", "setup": {"spot": "flat.start"}, "script": [{"ticks": 20}], "expect": {"none": true},
            "covers": ["movement.speed.a", "movement.fly.a"]},
           {"id": "ice", "category": "legit", "setup": {"spot": "ice.blue"}, "script": [{"ticks": 20}], "expect": {"none": true},
            "covers": ["movement.speed.a"]},
           {"id": "speed", "category": "cheat", "setup": {"spot": "flat.start"}, "script": [{"ticks": 20}],
            "cheat": {"mode": "speed"}, "expect": {"checks": ["movement.speed.a"]}},
           {"id": "reach", "category": "cheat", "setup": {"spot": "combat.player", "dummy": "combat.dummyfar"}, "script": [{"ticks": 20}],
            "cheat": {"mode": "reach"}, "expect": {"checks": ["combat.reach.a"]}}
         ]}
        """.trimIndent(),
        Stations::exists,
    )

    private fun result(id: String, index: Int, status: Status, vararg checks: CheckObservation) = ScenarioResult(
        index = index, id = id, title = id, category = catalog.scenarios.getValue(id).category.name.lowercase(),
        cheat = catalog.scenarios.getValue(id).cheatMode, lag = "none", status = status, reasons = listOf("Grund | mit Strich"),
        expected = catalog.scenarios.getValue(id).expect.checks,
        observation = ScenarioObservation(checks = checks.toList(), clientFinished = true, avgPing = 42.0), trace = "traces/00${index + 1}_$id.jsonl",
    )

    private val results = listOf(
        result("walk", 0, Status.PASS),
        result("ice", 1, Status.FAIL, CheckObservation("movement.speed.a", 4, 6.0, 6.0)),
        result("speed", 2, Status.PASS, CheckObservation("movement.speed.a", 5, 9.0, 9.0)),
        result("reach", 3, Status.SKIP),
    )
    private val report = RunReport(
        RunInfo(suite = "t", player = "GuardLab", clientVersion = "1.0", clientBrand = "labymod", toxiproxy = "ok", probe = "test"),
        results,
        Reports.coverage(catalog.scenarios, results),
    )

    @Test
    fun `coverage marks false alarms, detections and untested checks`() {
        val rows = report.coverage.associateBy { it.check }
        assertEquals(CheckCatalog.ALL, report.coverage.map { it.check })
        val speed = rows.getValue("movement.speed.a")
        assertEquals("Fehlalarm", speed.state)
        assertEquals(listOf("walk", "ice"), speed.legit)
        assertEquals(listOf("speed"), speed.detected)
        assertEquals(listOf("ice"), speed.flaggedLegit)
        assertEquals("nur legit", rows.getValue("movement.fly.a").state)
        assertEquals("ungetestet", rows.getValue("combat.reach.a").state) // skipped scenario does not count
        assertEquals("ungetestet", rows.getValue("movement.phase.a").state)
        assertTrue(rows.getValue("movement.phase.a").note.isNotBlank())
    }

    @Test
    fun `missed detection is reported`() {
        val missed = listOf(result("speed", 0, Status.FAIL))
        val row = Reports.coverage(catalog.scenarios, missed).first { it.check == "movement.speed.a" }
        assertEquals("nicht erkannt", row.state)
    }

    @Test
    fun `markdown, json and summary`() {
        val markdown = Reports.markdown(report)
        assertTrue(markdown.startsWith("# GuardLab-Bericht – Suite `t`"))
        assertTrue("## Fehlgeschlagen" in markdown)
        assertTrue("- **ice** (legit, none, Lag none)" in markdown)
        assertTrue("Grund \\| mit Strich" in markdown, "pipes must be escaped in table cells")
        assertTrue("## Abdeckung der Checks" in markdown)
        assertTrue("| `movement.speed.a` | Fehlalarm |" in markdown)
        assertEquals("suite=t passed=2 warned=0 failed=1 skipped=1 exempt=0", Reports.summary(report))
        val json = Json.parseToJsonElement(Reports.json(report)).jsonObject
        assertEquals(4, json["results"]!!.jsonArray.size)
        assertEquals("FAIL", json["results"]!!.jsonArray[1].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(4, json["results"]!!.jsonArray[1].jsonObject["observation"]!!.jsonObject["checks"]!!.jsonArray[0].jsonObject["flags"]!!.jsonPrimitive.int)
        assertEquals(32, json["coverage"]!!.jsonArray.size)
        val aborted = report.copy(info = report.info.copy(aborted = "Client getrennt"))
        assertTrue(Reports.summary(aborted).endsWith("aborted=\"Client getrennt\""))
        assertTrue("**Abgebrochen**" in Reports.markdown(aborted))
    }

    private val alert = { _: String -> 5.0 }

    @Test
    fun `calibration compares the loudest legit level with the quietest cheat level`() {
        val rows = Reports.calibration(catalog.scenarios, results, alert).associateBy { it.check }
        assertEquals(listOf("movement.fly.a", "movement.speed.a"), Reports.calibration(catalog.scenarios, results, alert).map { it.check })
        val speed = rows.getValue("movement.speed.a")
        assertEquals(6.0, speed.legitMax)
        assertEquals("ice", speed.legitScenario)
        assertEquals(9.0, speed.cheatMin)
        assertEquals("speed", speed.cheatScenario)
        assertTrue(speed.verdict.startsWith("Schwelle zu niedrig"), speed.verdict)
        assertEquals(0.0, rows.getValue("movement.fly.a").legitMax) // covered by walk, never rose
        assertTrue(rows.getValue("movement.fly.a").verdict.startsWith("nur legit-Daten"))
        val fine = listOf(
            result("walk", 0, Status.WARN, CheckObservation("movement.speed.a", 1, 1.2, 1.2)),
            result("speed", 1, Status.PASS, CheckObservation("movement.speed.a", 5, 9.0, 9.0)),
        )
        assertTrue(Reports.calibration(catalog.scenarios, fine, alert).first { it.check == "movement.speed.a" }.verdict.startsWith("passt"))
        val weak = listOf(result("speed", 0, Status.PASS, CheckObservation("movement.speed.a", 2, 3.0, 3.0)))
        assertTrue(Reports.calibration(catalog.scenarios, weak, alert).single().verdict.startsWith("Schwelle zu hoch"))
        val missed = listOf(result("speed", 0, Status.FAIL))
        assertTrue(Reports.calibration(catalog.scenarios, missed, alert).single().verdict.startsWith("nicht erkannt"))
        val speedOnly = FrameSummary(10, 10, 10, 0.0, 0.0, emptyList(), emptyList(), listOf(CheckActivity("movement.speed.a", 10, 0, 0, 0.0, 0.0, 5.0)))
        val evaluated = listOf(result("walk", 0, Status.PASS).let { it.copy(observation = it.observation.copy(frames = speedOnly)) })
        assertEquals(listOf("movement.speed.a"), Reports.calibration(catalog.scenarios, evaluated, alert).map { it.check }, "covered but never evaluated")
    }

    @Test
    fun `calibration counts one-of expectations only for the checks that rose`() {
        val anyOf = SuiteParser.parse(
            """
            {"suites": {"t": ["timer"]},
             "scenarios": [
               {"id": "timer", "category": "cheat", "setup": {"spot": "flat.start"}, "script": [{"ticks": 20}],
                "cheat": {"mode": "timer"}, "expect": {"checks": ["movement.timer.a", "movement.speed.a"]}}
             ]}
            """.trimIndent(),
            Stations::exists,
        )
        fun timer(vararg checks: CheckObservation) = ScenarioResult(
            index = 0, id = "timer", title = "timer", category = "cheat", cheat = "timer", lag = "none", status = Status.PASS,
            reasons = emptyList(), expected = emptyList(), observation = ScenarioObservation(checks = checks.toList(), clientFinished = true),
        )
        val one = Reports.calibration(anyOf.scenarios, listOf(timer(CheckObservation("movement.timer.a", 4, 6.0, 6.0))), alert)
        assertEquals(listOf("movement.timer.a"), one.map { it.check })
        val none = Reports.calibration(anyOf.scenarios, listOf(timer()), alert)
        assertEquals(setOf("movement.timer.a", "movement.speed.a"), none.map { it.check }.toSet())
    }

    @Test
    fun `markdown shows exempt passes, per-scenario details and the calibration`() {
        val frames = FrameSummary(
            frames = 420, ticks = 200, movementTicks = 200, allExemptShare = 0.0, movementExemptShare = 0.96,
            exemptions = listOf(Share("vehicle (MOVEMENT)", 192, 0.96)), tags = listOf(Share("vehicle", 200, 1.0)),
            checks = listOf(CheckActivity("movement.speed.a", 8, 0, 0, 0.0, 0.0, 5.0)),
        )
        val exempt = result("walk", 0, Status.PASS).copy(
            exempt = true,
            reasons = listOf("keine Meldung, aber kaum geprüft: Bewegungs-Checks ausgesetzt in 96 % der Ticks"),
            observation = ScenarioObservation(clientFinished = true, frames = frames),
            iguardTrace = "traces/001_walk.iguard.jsonl",
            expectation = "keine Meldung",
            covers = listOf("movement.speed.a", "movement.fly.a"),
        )
        val rows = listOf(exempt) + results.drop(1)
        val run = RunReport(report.info, rows, Reports.coverage(catalog.scenarios, rows), Reports.calibration(catalog.scenarios, rows, alert))
        val markdown = Reports.markdown(run)
        assertEquals(1, run.exemptPasses)
        assertTrue("(davon 1 nur, weil IGuard kaum prüfte – `pass (exempt)`)" in markdown)
        assertTrue("## Bestanden, aber kaum geprüft (`pass (exempt)`)" in markdown)
        assertTrue("| ✅ bestanden (exempt) |" in markdown)
        assertTrue("## Details je Szenario" in markdown)
        assertTrue("### 1. `walk` – walk" in markdown)
        assertTrue("- **Exemptions:** vehicle (MOVEMENT) 96 %" in markdown)
        assertTrue("- **Umgebung (Tags):** vehicle 100 %" in markdown)
        assertTrue("Bewegungs-Checks ausgesetzt: 96 % von 200 Bewegungs-Ticks" in markdown)
        assertTrue("`movement.speed.a` 8× geprüft, 0× fehlgeschlagen, 0× gezählt; `movement.fly.a` nie geprüft" in markdown)
        assertTrue("- **Traces:** GuardLab `traces/001_walk.jsonl`, IGuard `traces/001_walk.iguard.jsonl`" in markdown)
        assertTrue("- **IGuard-Prüfung:** keine Frame-Berichte" in markdown, "results without frames fall back to the VL rises")
        assertTrue("### 4. `reach` – reach" in markdown)
        assertTrue(markdown.indexOf("## Kalibrierung") > markdown.indexOf("## Abdeckung der Checks"))
        assertTrue("| `movement.speed.a` |" in markdown.substringAfter("## Kalibrierung"))
        assertTrue(Reports.summary(run).contains("exempt=1"))
        val failedSection = markdown.substringAfter("## Fehlgeschlagen").substringBefore("\n## ")
        assertFalse("walk" in failedSection, "local.sh reads the failures section up to the next ## heading")
        val json = Json.parseToJsonElement(Reports.json(run)).jsonObject
        assertEquals(true, json["results"]!!.jsonArray[0].jsonObject["exempt"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, json["calibration"]!!.jsonArray.size, "pass (exempt) scenarios do not calibrate")
    }
}
