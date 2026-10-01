package org.helix.addons.cinematic.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinematicSettingsTest {
    @Test
    fun `missing config means defaults on the lobby`() {
        val settings = CinematicSettings.from(emptyMap())
        assertTrue(settings.activeOn("lobby"))
        assertFalse(settings.activeOn("MineClash-Game"))
        assertEquals(30, settings.afkSeconds)
    }

    @Test
    fun `published config and opt-outs are read`() {
        val values = mapOf(
            "cinematic.config" to """{"tasks":["Hub"],"afkSeconds":45,"unknown":1}""",
            "cinematic.off.steve" to "1",
            "tablist.header" to "x",
        )
        val settings = CinematicSettings.from(values)
        assertTrue(settings.activeOn("hub"))
        assertEquals(45, settings.afkSeconds)
        assertEquals(setOf("steve"), CinematicSettings.optedOut(values))
    }
}
