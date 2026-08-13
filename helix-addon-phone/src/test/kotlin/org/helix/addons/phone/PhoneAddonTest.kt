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
    fun `apps carry the resolved built-in icon glyph`() {
        join("Steve")
        val messages = appsFor("Steve").first { it.id == "messages" }
        assertEquals(PhoneIcons.BUILTIN_FONT, messages.iconFont)
        assertEquals(String(Character.toChars(0xE001)), messages.iconChar)
    }

    @Test
    fun `admin apps are hidden without the admin permission`() {
        join("Steve")
        assertFalse(appsFor("Steve").any { it.id == "guard" })

        context.permissionCheck = { _, permission -> permission == "helix.phone.admin" || permission == "iguard.panel" }
        join("Admin")
        assertTrue(appsFor("Admin").any { it.id == "guard" })
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
