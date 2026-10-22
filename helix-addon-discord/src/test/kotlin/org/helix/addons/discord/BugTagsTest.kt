package org.helix.addons.discord

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BugTagsTest {
    private val available = mapOf("störend" to "1", "in arbeit" to "2", "netzwerk" to "3", "exploit" to "4")

    @Test
    fun `overrides win over name matching`() {
        assertEquals("99", BugTags.resolve("severity.annoying", mapOf("severity.annoying" to "99"), available))
        assertEquals("1", BugTags.resolve("severity.annoying", emptyMap(), available))
    }

    @Test
    fun `english keys and german labels both match`() {
        assertEquals("2", BugTags.resolve("status.in_progress", emptyMap(), available))
        assertEquals("4", BugTags.resolve("severity.exploit", emptyMap(), available))
        assertNull(BugTags.resolve("status.closed", emptyMap(), available))
    }

    @Test
    fun `report tags skip missing ones and classification falls back`() {
        val report = BugReport(1, severity = "annoying", category = "network", status = "closed")
        assertEquals(listOf("1", "3"), BugTags.forReport(report, emptyMap(), available))
        assertEquals("annoying" to "network", BugTags.classify(listOf("3", "1"), emptyMap(), available))
        assertEquals("minor" to "other", BugTags.classify(emptyList(), emptyMap(), available))
    }

    @Test
    fun `leaderboard schedule respects weekday hour and week`() {
        val zone = ZoneId.of("Europe/Berlin")
        val sundayEvening = Instant.parse("2026-10-18T17:00:00Z")
        assertTrue(BugBoard.due(sundayEvening, zone, 7, 18, null))
        assertFalse(BugBoard.due(sundayEvening.minusSeconds(7200), zone, 7, 18, null))
        assertFalse(BugBoard.due(sundayEvening, zone, 7, 18, BugBoard.weekKey(sundayEvening, zone)))
        assertEquals("## T\nleer", BugBoard.render(emptyList(), "T", "leer"))
    }
}
