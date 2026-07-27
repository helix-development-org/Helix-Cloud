package org.helix.addons.chat

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext

class PrettyChatAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("chat"))
    private val addon = PrettyChatAddon().also { it.onEnable(context) }

    @Test
    fun `format is published and updatable`() {
        assertTrue(context.bridgeValues["chat.format"]!!.contains("{message}"))

        assertTrue(context.run("chat.format", "{name}:", "{message}").success)
        assertEquals("{name}: {message}", context.bridgeValues["chat.format"])
        assertFalse(context.run("chat.format", "kaputt").success)
    }

    @Test
    fun `default format renders every display component`() {
        val format = context.bridgeValues["chat.format"]!!
        assertTrue(format.contains("{prefix}"), "group prefix renders via {prefix}")
        assertTrue(format.contains("{suffix}"), "clan tags render via {suffix}")
    }

    @Test
    fun `legacy configs with prefix rules and no suffix are migrated on enable`() {
        val storage = org.helix.api.storage.InMemoryAddonStorage()
        storage.write(
            "chat",
            """{"format":"{prefix}{color}{name} &8» &f{message}","rules":[{"permission":"x","prefix":"&cX ","color":"&c"}]}""",
        )
        val migratedContext = RecordingAddonContext(createTempDirectory("chat-migrate"), storage)
        PrettyChatAddon().onEnable(migratedContext)

        assertTrue(migratedContext.bridgeValues["chat.format"]!!.contains("{name}{suffix}"))
    }
}
