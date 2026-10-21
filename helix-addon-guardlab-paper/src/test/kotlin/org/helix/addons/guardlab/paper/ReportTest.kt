package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("suite=t passed=2 warned=0 failed=1 skipped=1", Reports.summary(report))
        val json = Json.parseToJsonElement(Reports.json(report)).jsonObject
        assertEquals(4, json["results"]!!.jsonArray.size)
        assertEquals("FAIL", json["results"]!!.jsonArray[1].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(4, json["results"]!!.jsonArray[1].jsonObject["observation"]!!.jsonObject["checks"]!!.jsonArray[0].jsonObject["flags"]!!.jsonPrimitive.int)
        assertEquals(32, json["coverage"]!!.jsonArray.size)
        val aborted = report.copy(info = report.info.copy(aborted = "Client getrennt"))
        assertTrue(Reports.summary(aborted).endsWith("aborted=\"Client getrennt\""))
        assertTrue("**Abgebrochen**" in Reports.markdown(aborted))
    }
}
