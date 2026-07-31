package org.helix.addons.discord

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.storage.InMemoryAddonStorage

class DiscordAddonTest {
    private fun enabledAddon(): RecordingAddonContext {
        val context = RecordingAddonContext(createTempDirectory("discord"))
        DiscordBotAddon().onEnable(context)
        return context
    }

    @Test
    fun `config writes defaults on first load and routes channels`() {
        val storage = InMemoryAddonStorage()

        val defaults = DiscordConfig.load(storage)

        assertFalse(defaults.configured())
        assertEquals(listOf("moderation"), defaults.notificationCategories)
        assertTrue(DiscordConfig.DEFAULT_CRITICAL.containsAll(listOf("platform.stop", "service.command")))
        assertTrue(storage.read("discord")!!.contains("botToken"))

        val routed = defaults.copy(
            auditChannelId = "audit",
            auditChannels = mapOf("link" to "links"),
            notificationChannelId = "notify",
            categoryChannels = mapOf("economy" to "eco"),
        )
        assertEquals("links", routed.channelForAudit("link"))
        assertEquals("audit", routed.channelForAudit("action"))
        assertEquals("eco", routed.channelForCategory("economy"))
        assertEquals("notify", routed.channelForCategory("moderation"))
    }

    @Test
    fun `a legacy channel id migrates to the notification channel`() {
        val storage = InMemoryAddonStorage()
        storage.write("discord", """{"botToken":"t","channelId":"123","commandPrefix":"!"}""")

        val migrated = DiscordConfig.load(storage)

        assertEquals("123", migrated.notificationChannelId)
        assertTrue(storage.read("discord")!!.contains("notificationChannelId"))
    }

    @Test
    fun `addon enables idle without token and reports status`() {
        val context = enabledAddon()

        val status = context.run("discord.status")

        assertTrue(status.success)
        assertTrue(status.lines.any { it == "configured: false" })
        assertTrue(status.lines.any { it == "connected: false" })
        assertEquals(1, context.notificationListeners.size)
        assertEquals(1, context.actionObservers.size)
        assertEquals(1, context.playerListeners.size)
        assertFalse(context.run("discord.send", "123", "hi").success)
    }

    @Test
    fun `link bootstrap actions manage links by player and discord id`() {
        val context = enabledAddon()
        context.recordJoin("Steve", "uuid-1")

        assertFalse(context.run("discord.link.set", "Unknown", "42").success)
        assertTrue(context.run("discord.link.set", "Steve", "42").success)
        assertFalse(context.run("discord.link.set", "Steve", "43").success)

        val list = context.run("discord.link.list")
        assertTrue(list.lines.single().contains("uuid-1"))
        assertTrue(list.lines.single().contains("42"))

        assertTrue(context.run("discord.link.remove", "Steve").success)
        assertEquals("no links", context.run("discord.link.list").lines.single())
        assertFalse(context.run("discord.link.remove", "Steve").success)
    }

    @Test
    fun `the in game discord command creates codes and unlinks`() {
        val context = enabledAddon()
        context.recordJoin("Steve", "uuid-1")

        assertTrue(context.run("discord", "Steve").success)
        assertFalse(context.run("discord", "Steve", "WRONGCODE").success)

        assertFalse(context.run("discord", "Steve", "unlink").success)
        context.run("discord.link.set", "Steve", "42")
        assertTrue(context.run("discord", "Steve", "unlink").success)
        assertEquals("no links", context.run("discord.link.list").lines.single())
    }

    @Test
    fun `the in game discord command is permission gated for bridges`() {
        val context = enabledAddon()
        val descriptor = context.handlers.getValue("discord").first

        assertTrue(descriptor.playerCommand)
        assertEquals(PermissionGate.LINK_NODE, descriptor.permission)
    }

    @Test
    fun `config set updates routing maps and tier lists`() {
        val context = enabledAddon()

        val result = context.run(
            "discord.config.set",
            "guild=1",
            "auditchannel=audit",
            "category.economy=eco",
            "audit.link=links",
            "critical=platform.stop,ban.set",
            "interval=120",
        )

        assertTrue(result.success)
        val config = DiscordConfig.load(context.storage)
        assertEquals("1", config.guildId)
        assertEquals("audit", config.auditChannelId)
        assertEquals(mapOf("economy" to "eco"), config.categoryChannels)
        assertEquals(mapOf("link" to "links"), config.auditChannels)
        assertEquals(listOf("platform.stop", "ban.set"), config.criticalActions)
        assertEquals(120, config.statusIntervalSeconds)
    }
}
