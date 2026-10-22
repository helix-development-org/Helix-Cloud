package org.helix.addons.mobs

import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobsAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("mobs"))
    private val addon = MobsAddon().also { it.onEnable(context) }

    private fun hints() = context.invocations.filter { it.action == "player.message" }

    private fun join(name: String) = context.playerListeners.forEach { it.onJoin(OnlinePlayer(name, uuid = "uuid-$name")) }

    @Test
    fun `hint is sent only on the first join by default`() {
        join("Steve")
        join("Steve")
        assertEquals(1, hints().size)
        assertEquals("Steve", hints().first().arguments.first())
        assertTrue(hints().first().arguments[1].contains("OptiFine"))
    }

    @Test
    fun `hint can be switched off and to every join`() {
        assertTrue(context.run("mobs.hint", "off").success)
        join("Alex")
        assertEquals(0, hints().size)
        context.run("mobs.hint", "always")
        join("Alex")
        join("Alex")
        assertEquals(2, hints().size)
    }
}
