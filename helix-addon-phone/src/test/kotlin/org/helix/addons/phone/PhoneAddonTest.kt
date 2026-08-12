package org.helix.addons.phone

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.helix.api.player.OnlinePlayer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneAddonTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val directory = createTempDirectory("phone")
    private val context = org.helix.addon.sdk.testing.RecordingAddonContext(directory)
    private val addon = PhoneAddon().also { it.onEnable(context) }

    private fun join(name: String) = context.playerListeners.forEach { it.onJoin(OnlinePlayer(name)) }

    private fun appsFor(player: String): List<AppView> {
        val result = context.run("phone.apps", player)
        assertTrue(result.success)
        return json.decodeFromString(ListSerializer(AppView.serializer()), result.lines.first())
    }

    @Test
    fun `get returns the default apps`() {
        val config = json.decodeFromString(PhoneConfig.serializer(), context.run("phone.get").lines.first())
        assertTrue(config.apps.any { it.id == "messages" })
    }

    @Test
    fun `apps carry the resolved built-in icon model`() {
        join("Steve")
        val messages = appsFor("Steve").firstOrNull { it.id == "messages" }
        // messages requires helix.bettermsgs, which the test context has no addon for →
        // it is filtered out; navigator is native (no requirement) and always present.
        assertEquals(null, messages)
        val navigator = appsFor("Steve").first { it.id == "navigator" }
        assertEquals(PhoneIcons.BUILTIN_CMD.getValue("navigator"), navigator.iconModel)
    }

    @Test
    fun `admin apps are hidden without the admin permission`() {
        join("Steve")
        // network is admin-only and native (no addon requirement)
        assertFalse(appsFor("Steve").any { it.id == "network" })

        context.permissionCheck = { _, permission -> permission == "helix.phone.admin" }
        join("Admin")
        assertTrue(appsFor("Admin").any { it.id == "network" })
    }

    @Test
    fun `apps whose required addon is absent are hidden`() {
        join("Steve")
        // messages/profile/guard require addons the test context has none of
        val visible = appsFor("Steve").map { it.id }
        assertFalse(visible.contains("messages"))
        assertFalse(visible.contains("profile"))
    }

    @Test
    fun `a newly added app only reaches players who joined after it`() {
        join("Old")
        assertTrue(context.run("phone.set", """{"apps":[{"id":"messages"},{"id":"calc"}]}""").success)
        join("New")

        assertFalse(appsFor("Old").any { it.id == "calc" }, "player online before the app was added must not see it")
        assertTrue(appsFor("New").any { it.id == "calc" }, "player who joined after must see it")
    }

    @Test
    fun `icon put rejects non-png and accepts a png`() {
        assertFalse(addon.let { context.run("phone.icon.put", "logo", "bm90LWEtcG5n").success }) // "not-a-png"
        val pngBase64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10, 1))
        assertTrue(context.run("phone.icon.put", "logo", pngBase64).success)
        assertTrue(context.run("phone.icons").lines.first().contains("logo"))
    }
}
