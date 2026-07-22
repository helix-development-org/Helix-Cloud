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

    @Test
    fun `import replaces frames and publishes the animated config`() {
        val payload = """{"headerFrames":["&6A","&6B","&6C"],"footerFrames":["&7x","&7y","&7z"],"intervalMs":100}"""

        assertTrue(context.run("tablist.import", payload).success)

        val published = context.bridgeValues["tablist.config"]!!
        assertTrue(published.contains("\"&6B\""))
        // interval below the minimum is clamped, base fields sync with frame 0
        assertTrue(published.contains("250"))
        assertEquals("&6A", context.bridgeValues["tablist.header"])
    }

    @Test
    fun `import rejects invalid json and too many frames`() {
        assertFalse(context.run("tablist.import", "{not json").success)
        val tooMany = (1..21).joinToString(",") { "\"f$it\"" }
        assertFalse(context.run("tablist.import", """{"headerFrames":[$tooMany]}""").success)
    }
}
