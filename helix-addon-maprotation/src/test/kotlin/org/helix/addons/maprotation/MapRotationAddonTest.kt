package org.helix.addons.maprotation

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext

class MapRotationAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("maprotation"))
    private val addon = MapRotationAddon().also { it.onEnable(context) }

    @Test
    fun `configure sets up the rotation and reports the current map`() {
        val result = context.run("maprotation.configure", "skywars", "island1,island2,island3")

        assertTrue(result.success)
        assertEquals("island1", context.run("maprotation.current", "skywars").lines.first())
        assertEquals("island2", context.run("maprotation.next", "skywars").lines.first())
    }

    @Test
    fun `advance moves current, broadcasts and updates bridge values`() {
        context.run("maprotation.configure", "skywars", "island1,island2")

        val advanced = context.run("maprotation.advance", "skywars")

        assertTrue(advanced.success)
        assertEquals("island2", advanced.lines.first())
        assertEquals("island2", context.run("maprotation.current", "skywars").lines.first())
        assertEquals("island1", context.run("maprotation.next", "skywars").lines.first())
        assertTrue(context.notifications.any { it.first == "maprotation" && it.second.contains("island1 -> island2") })
        assertEquals("island2", context.bridgeValues["maprotation.skywars.current"])
    }

    @Test
    fun `advance wraps around at the end of the list`() {
        context.run("maprotation.configure", "skywars", "island1,island2")
        context.run("maprotation.advance", "skywars")

        val wrapped = context.run("maprotation.advance", "skywars")

        assertEquals("island1", wrapped.lines.first())
    }

    @Test
    fun `unknown rotation actions fail clearly`() {
        assertFalse(context.run("maprotation.current", "unknown").success)
        assertFalse(context.run("maprotation.next", "unknown").success)
        assertFalse(context.run("maprotation.advance", "unknown").success)
    }

    @Test
    fun `list reports all configured rotations with their current map`() {
        context.run("maprotation.configure", "skywars", "island1,island2")
        context.run("maprotation.configure", "bedwars", "map1")

        val listed = context.run("maprotation.list")

        assertEquals(listOf("bedwars: map1", "skywars: island1"), listed.lines)
    }

    @Test
    fun `remove deletes a configured rotation`() {
        context.run("maprotation.configure", "skywars", "island1,island2")

        assertTrue(context.run("maprotation.remove", "skywars").success)
        assertFalse(context.run("maprotation.remove", "skywars").success)
        assertFalse(context.run("maprotation.current", "skywars").success)
    }
}
