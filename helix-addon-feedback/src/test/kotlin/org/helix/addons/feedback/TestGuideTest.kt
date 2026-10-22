package org.helix.addons.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestGuideTest {
    private val catalog = TestGuide.parse(assertNotNull(TestGuide.raw(), "testguide/commands.json missing"))

    @Test
    fun `the bundled catalog is valid`() {
        assertEquals(emptyList(), TestGuide.validate(catalog))
        assertTrue(catalog.entries.size > 200, "every in-game command is documented")
    }

    @Test
    fun `every mode and audience is covered`() {
        assertEquals(TestGuide.MODES.toSet(), catalog.entries.map { it.mode }.toSet())
        assertEquals(TestGuide.AUDIENCES.toSet(), catalog.entries.map { it.audience }.toSet())
    }

    @Test
    fun `new commands are documented`() {
        val commands = catalog.entries.map { it.command }.toSet()
        listOf("/clanwar challenge <Tag>", "/clanwar join", "/befehle", "/setlobbyspawn", "/clan create <Tag> <Name>", "/bug").forEach {
            assertTrue(it in commands, "$it missing")
        }
        assertTrue(catalog.entries.filter { it.command.startsWith("/test ") }.all { it.audience == "Team" }, "/test is team only")
    }

    @Test
    fun `validation reports broken entries`() {
        val broken = GuideCatalog(
            entries = listOf(
                GuideEntry("a", "Mond", "/x", "s", "e", "Spieler"),
                GuideEntry("a", "Lobby", "x", "", "e", "Alle"),
                GuideEntry("b", "Lobby", "/x", "s", "e", "Team"),
            ),
        )
        val problems = TestGuide.validate(broken)
        assertTrue(problems.any { "unknown mode" in it })
        assertTrue(problems.any { "unknown audience" in it })
        assertTrue(problems.any { "must start" in it })
        assertTrue(problems.any { "required" in it })
        assertTrue(problems.any { "duplicate id a" in it })
        assertTrue(problems.any { "duplicate command /x" in it })
    }
}
