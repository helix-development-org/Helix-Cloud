package org.helix.addons.subtitles

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.addon.ProfileSettingType
import org.helix.api.player.OnlinePlayer

class SubtitleAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("subtitles"))

    /** Simulates the profile addon's own `profile.setting.get`, backed by this map. */
    private val storedValues = mutableMapOf<String, String>()

    private fun installFakeProfileGet() {
        context.registerAction(ActionDescriptor("profile.setting.get", "d", "u")) { invocation ->
            val (player, _, key) = invocation.arguments
            ActionResult.ok(storedValues["$player:$key"] ?: if (key == "subtitle") "none" else "")
        }
    }

    private fun enableWith() {
        installFakeProfileGet()
        SubtitleAddon().also { it.onEnable(context) }
    }

    @Test
    fun `settingsFor exposes the predefined list without custom for a player lacking the permission`() {
        enableWith()
        context.run("subtitle.config.add", "veteran", "Veteran")

        val descriptors = context.profileSettingProviders.single().settingsFor("steve")

        assertEquals(1, descriptors.size)
        val type = descriptors.single().type as ProfileSettingType.Choice
        assertEquals(listOf("none", "veteran"), type.options.map { it.id })
    }

    @Test
    fun `settingsFor adds the custom setting once the player has the permission`() {
        enableWith()
        context.permissionCheck = { _, permission -> permission == "helix.subtitle.custom" }

        val descriptors = context.profileSettingProviders.single().settingsFor("steve")

        assertEquals(setOf("subtitle", "custom"), descriptors.map { it.key }.toSet())
    }

    @Test
    fun `a rank-gated predefined subtitle is shown locked without the permission`() {
        enableWith()
        context.run("subtitle.config.add", "legend", "Legend", "helix.subtitle.legend")

        val type = context.profileSettingProviders.single().settingsFor("steve").first().type as ProfileSettingType.Choice
        val legend = type.options.single { it.id == "legend" }

        assertFalse(legend.unlocked)

        context.permissionCheck = { _, permission -> permission == "helix.subtitle.legend" }
        val unlockedType = context.profileSettingProviders.single().settingsFor("steve").first().type as ProfileSettingType.Choice
        assertTrue(unlockedType.options.single { it.id == "legend" }.unlocked)
    }

    @Test
    fun `choosing a predefined subtitle publishes its text as a bridge value`() {
        enableWith()
        context.run("subtitle.config.add", "veteran", "Veteran Player")
        storedValues["steve:subtitle"] = "veteran"

        context.profileSettingProviders.single().onChanged("steve", "subtitle", "veteran")

        assertEquals("Veteran Player", context.bridgeValues["subtitle.text.steve"])
    }

    @Test
    fun `a custom subtitle overrides the chosen predefined one`() {
        enableWith()
        context.run("subtitle.config.add", "veteran", "Veteran Player")
        storedValues["steve:subtitle"] = "veteran"
        storedValues["steve:custom"] = "My Own Text"

        context.profileSettingProviders.single().onChanged("steve", "custom", "My Own Text")

        assertEquals("My Own Text", context.bridgeValues["subtitle.text.steve"])
    }

    @Test
    fun `clearing back to none unpublishes the bridge value`() {
        enableWith()
        context.run("subtitle.config.add", "veteran", "Veteran Player")
        storedValues["steve:subtitle"] = "veteran"
        context.profileSettingProviders.single().onChanged("steve", "subtitle", "veteran")
        assertTrue(context.bridgeValues.containsKey("subtitle.text.steve"))

        storedValues["steve:subtitle"] = "none"
        context.profileSettingProviders.single().onChanged("steve", "subtitle", "none")

        assertFalse(context.bridgeValues.containsKey("subtitle.text.steve"))
    }

    @Test
    fun `joining republishes an existing choice, leaving unpublishes`() {
        enableWith()
        context.run("subtitle.config.add", "veteran", "Veteran Player")
        storedValues["steve:subtitle"] = "veteran"

        context.playerListeners.single().onJoin(OnlinePlayer(name = "steve"))
        assertEquals("Veteran Player", context.bridgeValues["subtitle.text.steve"])

        context.playerListeners.single().onLeave(OnlinePlayer(name = "steve"))
        assertNull(context.bridgeValues["subtitle.text.steve"])
    }

    @Test
    fun `config add remove and list round-trip`() {
        enableWith()

        assertTrue(context.run("subtitle.config.add", "veteran", "Veteran Player").success)
        assertTrue(context.run("subtitle.config.list").lines.first().contains("veteran"))
        assertTrue(context.run("subtitle.config.remove", "veteran").success)
        assertEquals("no predefined subtitles", context.run("subtitle.config.list").lines.first())
        assertFalse(context.run("subtitle.config.remove", "veteran").success)
    }
}
