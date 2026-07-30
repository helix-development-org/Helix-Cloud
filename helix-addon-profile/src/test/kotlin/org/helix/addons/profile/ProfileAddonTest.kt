package org.helix.addons.profile

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.addon.ProfileInfoEntry
import org.helix.api.addon.ProfileInfoProvider
import org.helix.api.addon.ProfileSettingDescriptor
import org.helix.api.addon.ProfileSettingOption
import org.helix.api.addon.ProfileSettingProvider
import org.helix.api.addon.ProfileSettingType

class ProfileAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("profile"))
    private val addon = ProfileAddon().also { it.onEnable(context) }

    private fun wingsDescriptor(unlockedAngel: Boolean = true) = ProfileSettingDescriptor(
        key = "wings",
        label = "Wings",
        type = ProfileSettingType.Choice(
            listOf(
                ProfileSettingOption("none", "None"),
                ProfileSettingOption("angel", "Angel Wings", unlocked = unlockedAngel),
            ),
        ),
        default = "none",
    )

    @Test
    fun `view aggregates info and settings with resolved defaults`() {
        context.registerProfileInfoProvider(object : ProfileInfoProvider {
            override fun infoFor(player: String) = listOf(ProfileInfoEntry("Kills", "42"))
        })
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor())
        })

        val result = context.run("profile.view", "steve")

        assertTrue(result.success)
        assertTrue(result.lines.first().contains("\"Kills\""))
        assertTrue(result.lines.first().contains("\"default\": \"none\""))
    }

    @Test
    fun `get reads back the current value, falling back to the descriptor default`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor())
        })

        assertEquals("none", context.run("profile.setting.get", "steve", "provider-0", "wings").lines.first())

        context.run("profile.setting.set", "steve", "provider-0", "wings", "angel")

        assertEquals("angel", context.run("profile.setting.get", "steve", "provider-0", "wings").lines.first())
    }

    @Test
    fun `setting an unlocked option persists and notifies the owner`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor())
        })

        val result = context.run("profile.setting.set", "steve", "provider-0", "wings", "angel")

        assertTrue(result.success)
        assertEquals(
            listOf(RecordingAddonContext.ProfileSettingChange("provider-0", "steve", "wings", "angel")),
            context.profileSettingChanges,
        )
    }

    @Test
    fun `setting a locked option is rejected for self-service`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor(unlockedAngel = false))
        })

        val result = context.run("profile.setting.set", "steve", "provider-0", "wings", "angel")

        assertFalse(result.success)
        assertTrue(context.profileSettingChanges.isEmpty(), "a rejected set must not notify")
    }

    @Test
    fun `admin-set bypasses per-option gating`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor(unlockedAngel = false))
        })

        val result = context.run("profile.setting.admin-set", "steve", "provider-0", "wings", "angel")

        assertTrue(result.success)
    }

    @Test
    fun `admin-set still rejects a value that is not a declared option`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor())
        })

        val result = context.run("profile.setting.admin-set", "steve", "provider-0", "wings", "invented")

        assertFalse(result.success)
    }

    @Test
    fun `toggle only accepts true or false`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(
                ProfileSettingDescriptor("particles", "Particles", ProfileSettingType.Toggle, default = "false"),
            )
        })

        assertTrue(context.run("profile.setting.set", "steve", "provider-0", "particles", "true").success)
        assertFalse(context.run("profile.setting.set", "steve", "provider-0", "particles", "maybe").success)
    }

    @Test
    fun `free text rejects values past the configured max length`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(
                ProfileSettingDescriptor("subtitle", "Subtitle", ProfileSettingType.FreeText(maxLength = 5)),
            )
        })

        assertTrue(context.run("profile.setting.set", "steve", "provider-0", "subtitle", "short").success)
        assertFalse(context.run("profile.setting.set", "steve", "provider-0", "subtitle", "way too long").success)
    }

    @Test
    fun `a provider's validate hook can reject a value the type system would allow`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(
                ProfileSettingDescriptor("nick", "Nickname", ProfileSettingType.FreeText()),
            )
            override fun validate(player: String, key: String, value: String): String? =
                if (value.equals("Admin", ignoreCase = true)) "that name is reserved" else null
        })

        val rejected = context.run("profile.setting.set", "steve", "provider-0", "nick", "Admin")
        val accepted = context.run("profile.setting.set", "steve", "provider-0", "nick", "Steve2")

        assertFalse(rejected.success)
        assertTrue(accepted.success)
    }

    @Test
    fun `clear resets a value and notifies with the descriptor default`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor())
        })
        context.run("profile.setting.set", "steve", "provider-0", "wings", "angel")

        context.run("profile.setting.clear", "steve", "provider-0", "wings")

        val view = context.run("profile.view", "steve")
        assertTrue(view.lines.first().contains("\"current\": \"none\""))
        assertEquals("none", context.profileSettingChanges.last().value)
    }

    @Test
    fun `the player command with no arguments renders a readable summary`() {
        context.registerProfileInfoProvider(object : ProfileInfoProvider {
            override fun infoFor(player: String) = listOf(ProfileInfoEntry("Kills", "42"))
        })

        val result = context.run("profile", "steve")

        assertTrue(result.success)
        assertTrue(result.lines.any { it.contains("Kills") && it.contains("42") })
    }

    @Test
    fun `the player command's set subcommand resolves the owner by key`() {
        context.registerProfileSettingProvider(object : ProfileSettingProvider {
            override fun settingsFor(player: String) = listOf(wingsDescriptor())
        })

        val result = context.run("profile", "steve", "set", "wings", "angel")

        assertTrue(result.success)
        assertTrue(context.run("profile.view", "steve").lines.first().contains("\"current\": \"angel\""))
    }

    @Test
    fun `the player command's set subcommand reports an unknown key`() {
        val result = context.run("profile", "steve", "set", "nonexistent", "value")

        assertFalse(result.success)
    }

}
