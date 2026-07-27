package org.helix.addons.nick

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer

class NickAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("nick"))
    private val addon = NickAddon().also { it.onEnable(context) }

    private fun resolve(name: String) = context.displayResolvers.single().resolve(name)

    @Test
    fun `nick resolves as the display name component`() {
        assertTrue(context.run("nick", "Steve", "Herobrine").success)

        assertEquals("Herobrine", resolve("Steve")?.name)
        assertEquals("", resolve("Steve")?.prefix)
        assertNull(resolve("Alex"))
    }

    @Test
    fun `nick off restores the real name`() {
        context.run("nick", "Steve", "Herobrine")

        assertTrue(context.run("nick", "Steve", "off").success)
        assertNull(resolve("Steve"))
        assertFalse(context.run("nick", "Steve", "off").success)
    }

    @Test
    fun `invalid shapes and impersonation are rejected`() {
        context.online += OnlinePlayer(name = "Alex")
        context.run("nick", "Notch", "Taken")

        assertFalse(context.run("nick", "Steve", "ab").success)
        assertFalse(context.run("nick", "Steve", "way_too_long_for_a_name").success)
        assertFalse(context.run("nick", "Steve", "spaced name").success)
        assertFalse(context.run("nick", "Steve", "alex").success)
        assertFalse(context.run("nick", "Steve", "TAKEN").success)
        assertTrue(context.run("nick", "Steve", "steve").success, "own name recasing stays allowed")
    }

    @Test
    fun `nicks persist across restarts and admin clear removes them`() {
        context.run("nick", "Steve", "Herobrine")

        // Simulated restart: a fresh addon instance on the same storage.
        NickAddon().also { it.onEnable(context) }
        val restartedResolver = context.displayResolvers.last()
        assertEquals("Herobrine", restartedResolver.resolve("Steve")?.name)

        assertTrue(context.run("nick.clear", "Steve").success)
        assertNull(restartedResolver.resolve("Steve"))
    }
}
