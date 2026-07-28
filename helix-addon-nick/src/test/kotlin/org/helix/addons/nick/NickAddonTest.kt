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
    fun `nick resolves as an exclusive disguise profile`() {
        assertTrue(context.run("nick", "Steve", "Herobrine").success)

        val profile = resolve("Steve")
        assertEquals("Herobrine", profile?.name)
        assertEquals("", profile?.prefix, "default disguise is a plain player")
        assertTrue(profile?.exclusive == true, "group prefix and clan tag must not leak")
        assertEquals("Herobrine", context.bridgeValues["nick.name.steve"], "bridges are notified")
        assertNull(resolve("Alex"))
    }

    @Test
    fun `disguise prefix is configurable and applied to nicked players`() {
        assertTrue(context.run("nick.disguise", "&7Spieler").success)
        context.run("nick", "Steve", "Herobrine")

        assertEquals("&7Spieler ", resolve("Steve")?.prefix)
        assertEquals("&7Spieler Herobrine", resolve("Steve")?.displayName("Steve"))

        assertTrue(context.run("nick.disguise", "clear").success)
        assertEquals("", resolve("Steve")?.prefix)
    }

    @Test
    fun `nick off restores the real name`() {
        context.run("nick", "Steve", "Herobrine")

        assertTrue(context.run("nick", "Steve", "off").success)
        assertNull(resolve("Steve"))
        assertEquals("", context.bridgeValues["nick.name.steve"], "bridges see the removal")
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
    fun `nicking to an offline premium account is rejected`() {
        context.recordJoin("ghost", "11111111-1111-1111-1111-111111111111")

        assertFalse(context.run("nick", "Steve", "Ghost").success)
    }

    @Test
    fun `nicking to a staff member is rejected`() {
        context.permissionCheck = { player, permission -> player.equals("Admin", ignoreCase = true) && permission == "helix.admin" }

        assertFalse(context.run("nick", "Steve", "Admin").success)
    }

    @Test
    fun `nicking to a genuinely unknown name is allowed`() {
        assertTrue(context.run("nick", "Steve", "Freshname").success)
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
