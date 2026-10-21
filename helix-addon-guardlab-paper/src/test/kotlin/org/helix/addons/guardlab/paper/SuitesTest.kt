package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuitesTest {
    private val bundled = SuiteParser.parse(
        javaClass.getResource("/suites.json")!!.readText(),
        Stations::exists,
    )

    @Test
    fun `bundled suites parse against the station layout`() {
        assertTrue(bundled.scenarios.size >= 40, "only ${bundled.scenarios.size} scenarios")
        for (id in listOf("all", "legit", "cheats", "lag", "smoke")) {
            assertTrue(bundled.suite(id).orEmpty().isNotEmpty(), "suite $id empty")
        }
        val all = bundled.suite("all")!!
        assertEquals(bundled.scenarios.size, all.size)
        assertEquals(Category.entries.toSet(), all.map { it.category }.toSet())
        // legit first, then cheats, then lag – and the once-per-connection client scenarios at the very end
        val perConnection = listOf("cheat_identity", "cheat_brandspoof")
        assertEquals(perConnection, all.takeLast(2).map { it.id })
        val rest = all.dropLast(2)
        assertEquals(rest.sortedBy { it.category.ordinal }.map { it.id }, rest.map { it.id })
        assertTrue(bundled.suite("cheats")!!.all { it.category == Category.CHEAT })
        assertEquals(perConnection, bundled.suite("client")!!.takeLast(2).map { it.id })
    }

    @Test
    fun `killaura is split into snap and the smooth known gap, reach targets beyond vanilla range`() {
        assertTrue("cheat_killaura" !in bundled.scenarios)
        val snap = bundled.scenarios.getValue("cheat_killaura_snap")
        assertTrue(snap.expect.checks.containsAll(listOf("combat.snapaim.a", "combat.rotation.a", "combat.autoclicker.a")))
        assertFalse(snap.expect.knownGap)
        assertEquals(listOf("cheat_killaura_smooth", "cheat_autoclicker_jitter"), bundled.scenarios.values.filter { it.expect.knownGap }.map { it.id })
        val reach = bundled.scenarios.getValue("cheat_reach")
        val player = Stations.resolve(reach.setup.spot)!!
        val dummy = Stations.resolve(reach.setup.dummy!!)!!
        val gap = dummy.z - player.z - 0.3
        val range = reach.cheat["params"]!!.jsonObject["range"]!!.jsonPrimitive.double
        assertTrue(gap > 3.3 && gap < range - 0.5, "hitbox gap $gap, range $range")
    }

    @Test
    fun `every IGuard check is exercised or expected`() {
        val missing = CheckCatalog.ALL.toSet() - bundled.coveredChecks
        assertTrue(missing.isEmpty(), "uncovered checks: $missing")
        val unknown = bundled.coveredChecks - CheckCatalog.ALL.toSet()
        assertTrue(unknown.isEmpty(), "unknown check ids: $unknown")
        assertEquals(32, CheckCatalog.ALL.toSet().size)
        // every check without a cheat scenario is explained
        val expected = bundled.scenarios.values.flatMap { it.expect.checks + it.expect.all }.toSet()
        val notProvoked = CheckCatalog.ALL.filter { it !in expected }.toSet()
        assertEquals(CheckCatalog.NOT_PROVOKED.keys, notProvoked)
    }

    @Test
    fun `bundled scenarios use only contract cheat modes and sane timings`() {
        bundled.scenarios.values.forEach { scenario ->
            assertTrue(scenario.cheatMode in SuiteParser.CHEAT_MODES, scenario.id)
            assertTrue(scenario.durationTicks in 1..6000, scenario.id)
            if (scenario.category == Category.LEGIT) assertEquals("none", scenario.cheatMode, scenario.id)
            if (scenario.setup.dummy == null) assertTrue(scenario.dummyScript.isEmpty(), scenario.id)
        }
    }

    @Test
    fun `parser reports every problem`() {
        val text = """
            {
              "suites": {"x": ["a", "missing", "@bogus"]},
              "scenarios": [
                {"id": "a", "category": "legit", "setup": {"spot": "flat.start"}, "script": [{"ticks": 10, "keys": ["forward"]}], "expect": {"none": true}},
                {"id": "a", "category": "legit", "setup": {"spot": "flat.start"}, "script": [], "expect": {"none": true}},
                {"id": "b", "category": "cheat", "setup": {"spot": "nowhere.x"}, "script": [{"ticks": 0, "keys": ["fly"], "walk": 1}],
                 "cheat": {"mode": "warp"}, "lag": "ping9000", "expect": {"none": true}},
                {"id": "c", "category": "legit", "setup": {"spot": "flat.start", "mob": "combat.nope"}, "script": [{"ticks": 30}],
                 "durationTicks": 10, "expect": {"none": true, "checks": ["movement.fly.a"]}},
                {"id": "d", "category": "legit", "setup": {"spot": "flat.start"}, "script": [{"ticks": 5}], "expect": {"none": true, "knownGap": true}}
              ]
            }
        """.trimIndent()
        val error = assertFailsWith<IllegalArgumentException> { SuiteParser.parse(text, Stations::exists) }
        val message = error.message.orEmpty()
        listOf(
            "a: duplicate id", "unknown spot nowhere.x", "unknown fields [walk]", "ticks must be 1..2400", "unknown key fly",
            "unknown cheat mode warp", "unknown lag profile ping9000", "a cheat scenario must expect checks",
            "unknown mob spot combat.nope", "durationTicks 10 shorter", "expect none excludes checks",
            "unknown scenario missing", "unknown selector @bogus", "d: knownGap needs expected checks",
        ).forEach { assertTrue(it in message, "missing '$it' in:\n$message") }
    }

    @Test
    fun `defaults and selectors expand`() {
        val catalog = SuiteParser.parse(
            """
            {"suites": {"s": ["@cheat", "l"]},
             "scenarios": [
               {"id": "l", "category": "legit", "setup": {"spot": "flat.start"}, "script": [{"ticks": 40}, {"ticks": 20}], "expect": {"none": true}},
               {"id": "c", "category": "cheat", "setup": {"spot": "flat.start"}, "script": [{"ticks": 10}],
                "cheat": {"mode": "speed", "params": {"factor": 2}}, "expect": {"checks": ["movement.speed.a"], "minVl": 2.5}}
             ]}
            """.trimIndent(),
        )
        assertEquals(listOf("c", "l"), catalog.suite("s")!!.map { it.id })
        val moved = SuiteParser.parse(
            """
            {"suites": {"s": ["@legit", "a"]},
             "scenarios": [
               {"id": "a", "category": "legit", "setup": {"spot": "flat.start"}, "script": [{"ticks": 5}], "expect": {"none": true}},
               {"id": "b", "category": "legit", "setup": {"spot": "flat.start"}, "script": [{"ticks": 5}], "expect": {"none": true}}
             ]}
            """.trimIndent(),
        )
        assertEquals(listOf("b", "a"), moved.suite("s")!!.map { it.id }, "a listed again runs at its last position")
        val legit = assertNotNull(catalog.scenarios["l"])
        assertEquals(80, legit.durationTicks)
        assertEquals(60, legit.setupTicks)
        assertEquals("none", legit.cheatMode)
        assertEquals("none", legit.lag)
        assertEquals("speed", catalog.scenarios["c"]!!.cheatMode)
        assertEquals(2.5, catalog.scenarios["c"]!!.expect.minVl)
    }
}
