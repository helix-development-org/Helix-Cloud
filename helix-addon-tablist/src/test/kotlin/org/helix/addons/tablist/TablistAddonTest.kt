package org.helix.addons.tablist

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TablistAddonTest {
    private val directory = createTempDirectory("tablist")
    private val context = org.helix.addon.sdk.testing.RecordingAddonContext(directory)
    private val addon = TablistAddon().also { it.onEnable(context) }

    @Test
    fun `defaults are published on enable`() {
        assertEquals("&6Helix-Cloud", context.bridgeValues["tablist.header"])
        assertTrue(context.bridgeValues["tablist.footer"]!!.contains("{online}"))
    }

    @Test
    fun `header update publishes and persists with line breaks`() {
        assertTrue(context.run("tablist.header", "&6Mein", "Netzwerk\\nZeile2").success)

        assertEquals("&6Mein Netzwerk\nZeile2", context.bridgeValues["tablist.header"])

        val second = org.helix.addon.sdk.testing.RecordingAddonContext(directory, context.storage)
        TablistAddon().onEnable(second)
        assertEquals("&6Mein Netzwerk\nZeile2", second.bridgeValues["tablist.header"])
    }

    @Test
    fun `show displays configuration and blank input fails`() {
        assertTrue(context.run("tablist.show").lines.any { it.startsWith("header:") })
        assertFalse(context.run("tablist.footer").success)
    }
}
