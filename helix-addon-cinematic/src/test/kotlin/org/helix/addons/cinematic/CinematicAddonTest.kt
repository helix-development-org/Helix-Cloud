package org.helix.addons.cinematic

import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.player.OnlinePlayer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CinematicAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("cinematic"))
    private val profile = mutableMapOf<String, String>()

    private fun enable() {
        context.registerAction(ActionDescriptor("profile.setting.get", "d", "u")) { inv ->
            ActionResult.ok(profile[inv.arguments[0]] ?: "true")
        }
        CinematicAddon().also { it.onEnable(context) }
    }

    private fun run(action: String, vararg args: String) =
        context.actions.invoke(ActionInvocation(action, args.toList(), ActionSource.CLI))

    @Test
    fun `defaults are published and the lobby is on`() {
        enable()
        val config = CinematicConfig.parse(context.bridgeValues["cinematic.config"])
        assertEquals(listOf("Lobby"), config.tasks)
        assertEquals(30, config.afkSeconds)
    }

    @Test
    fun `settings are validated, stored and republished`() {
        enable()
        assertTrue(run("cinematic.set", "tasks", "Lobby,", "Hub").success)
        assertTrue(run("cinematic.set", "afkSeconds", "45").success)
        assertFalse(run("cinematic.set", "afkSeconds", "2").success)
        assertFalse(run("cinematic.set", "nope", "1").success)
        val config = CinematicConfig.parse(context.bridgeValues["cinematic.config"])
        assertEquals(listOf("Lobby", "Hub"), config.tasks)
        assertEquals(45, config.afkSeconds)
        assertEquals(config, CinematicConfig.parse(context.storage.read("config")))
    }

    @Test
    fun `players who turned it off are published`() {
        profile["Steve"] = "false"
        enable()
        context.playerListeners.single().onJoin(OnlinePlayer(name = "Steve", uuid = "u1", proxyServiceId = "Proxy-1"))
        assertEquals("1", context.bridgeValues["cinematic.off.steve"])
        context.profileSettingProviders.single().onChanged("Steve", "cinematic", "true")
        assertNull(context.bridgeValues["cinematic.off.steve"])
    }
}
