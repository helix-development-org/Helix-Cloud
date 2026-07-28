package org.helix.addons.stats

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext

class StatsAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("stats"))
    private val addon = StatsAddon().also { it.onEnable(context) }

    @Test
    fun `stats add and get round-trip through actions`() {
        assertTrue(context.run("stats.add", "kills", "Steve", "5").success)
        assertEquals("5", context.run("stats.get", "kills", "Steve").lines.first())

        context.run("stats.add", "kills", "Steve", "3")
        assertEquals("8", context.run("stats.get", "kills", "steve").lines.first())
    }

    @Test
    fun `stats set overwrites and stats list reports known keys`() {
        context.run("stats.add", "kills", "Steve", "5")
        context.run("stats.set", "kills", "Steve", "100")
        context.run("stats.add", "deaths", "Steve", "1")

        assertEquals("100", context.run("stats.get", "kills", "Steve").lines.first())
        assertEquals(listOf("deaths", "kills"), context.run("stats.list").lines)
    }

    @Test
    fun `stats top returns a sorted leaderboard`() {
        context.run("stats.set", "kills", "alex", "10")
        context.run("stats.set", "kills", "steve", "20")
        context.run("stats.set", "kills", "bob", "5")

        val top = context.run("stats.top", "kills", "2")

        assertEquals(listOf("#1 steve - 20", "#2 alex - 10"), top.lines)
    }

    @Test
    fun `player command shows own value by default and another player's when named`() {
        context.run("stats.set", "kills", "steve", "42")

        val own = context.run("stats", "Steve", "kills")
        assertTrue(own.lines.first().contains("42"))

        val other = context.run("stats", "Alex", "kills", "Steve")
        assertTrue(other.lines.first().contains("Steve"))
        assertTrue(other.lines.first().contains("42"))
    }

    @Test
    fun `player command top subcommand lists the leaderboard`() {
        context.run("stats.set", "kills", "steve", "20")
        context.run("stats.set", "kills", "alex", "10")

        val result = context.run("stats", "Steve", "top", "kills")

        assertTrue(result.lines[0].contains("#1"))
        assertTrue(result.lines[0].contains("steve"))
    }

    @Test
    fun `player command top on an empty stat reports emptiness`() {
        val result = context.run("stats", "Steve", "top", "unknownstat")
        assertTrue(result.success)
        assertTrue(result.lines.first().contains("unknownstat"))
    }

    @Test
    fun `season reset archives and clears then can be viewed`() {
        context.run("stats.set", "kills", "steve", "30")
        context.run("stats.set", "kills", "alex", "10")

        val reset = context.run("stats.season.reset", "kills")
        assertTrue(reset.success)
        assertEquals("0", context.run("stats.get", "kills", "steve").lines.first())

        val view = context.run("stats.season.view", "kills", "1")
        assertEquals(listOf("#1 steve - 30", "#2 alex - 10"), view.lines)

        context.run("stats.set", "kills", "steve", "5")
        val listed = context.run("stats.season.list", "kills")
        assertTrue(listed.lines.single().contains("season 1"))
    }

    @Test
    fun `season reset on an empty stat fails without archiving`() {
        val result = context.run("stats.season.reset", "neverplayed")
        assertFalse(result.success)
        assertTrue(context.run("stats.season.list", "neverplayed").lines.isEmpty())
    }

    @Test
    fun `viewing an unknown season fails`() {
        context.run("stats.set", "kills", "steve", "1")
        context.run("stats.season.reset", "kills")

        assertFalse(context.run("stats.season.view", "kills", "99").success)
    }
}
