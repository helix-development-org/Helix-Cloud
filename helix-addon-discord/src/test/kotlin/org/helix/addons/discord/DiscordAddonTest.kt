package org.helix.addons.discord

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult

class DiscordAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("discord"))
    private val config = DiscordConfig(
        botToken = "token",
        channelId = "123",
        adminUserIds = listOf("42"),
    )
    private val handler = DiscordCommandHandler(context.actions, { config })

    init {
        context.registerAction(ActionDescriptor("platform.overview", "overview", "platform.overview")) {
            ActionResult.ok("Helix-Cloud 1.5.0", "&7services: 2/2")
        }
        context.registerAction(ActionDescriptor("player.list", "players", "player.list")) {
            ActionResult.ok("2 online: Steve, Alex")
        }
        context.registerAction(ActionDescriptor("service.start", "starts", "service.start <task>")) { invocation ->
            ActionResult.ok("started ${invocation.arguments.first()}-1")
        }
    }

    @Test
    fun `status and players commands answer with stripped colors in code blocks`() {
        val status = handler.handle("7", false, "123", "!status")

        assertTrue(status!!.startsWith("```"))
        assertTrue(status.contains("services: 2/2"))
        assertFalse(status.contains("&7"))
        assertTrue(handler.handle("7", false, "123", "!players")!!.contains("Steve"))
    }

    @Test
    fun `bots other channels and non-commands are ignored`() {
        assertNull(handler.handle("7", true, "123", "!status"))
        assertNull(handler.handle("7", false, "999", "!status"))
        assertNull(handler.handle("7", false, "123", "hello"))
        assertNull(handler.handle("7", false, "123", "!unknown"))
    }

    @Test
    fun `run is admin gated and forwards to actions`() {
        assertEquals(
            "You are not allowed to run actions.",
            handler.handle("7", false, "123", "!run service.start Lobby"),
        )

        val allowed = handler.handle("42", false, "123", "!run service.start Lobby")

        assertTrue(allowed!!.contains("started Lobby-1"))
        assertEquals(listOf("Lobby"), context.invocations.single { it.action == "service.start" }.arguments)
    }

    @Test
    fun `help lists the commands with the configured prefix`() {
        val help = handler.handle("7", false, "123", "!help")

        assertTrue(help!!.contains("!status"))
        assertTrue(help.contains("!run"))
    }

    @Test
    fun `color codes are stripped`() {
        assertEquals("[Ban] steve — griefing", handler.stripColors("&c[Ban] &fsteve &7— griefing"))
    }

    @Test
    fun `config writes defaults on first load and persists edits`() {
        val file = createTempDirectory("discord").resolve("discord.json")

        val defaults = DiscordConfig.load(file)

        assertFalse(defaults.configured())
        assertEquals("!", defaults.commandPrefix)
        assertEquals(listOf("moderation"), defaults.notificationCategories)
        assertTrue(file.toFile().readText().contains("botToken"))
    }

    @Test
    fun `addon enables idle without token and reports status`() {
        val addonContext = RecordingAddonContext(createTempDirectory("discord"))
        DiscordBotAddon().onEnable(addonContext)

        val status = addonContext.run("discord.status")

        assertTrue(status.success)
        assertTrue(status.lines.any { it == "configured: false" })
        assertTrue(status.lines.any { it == "connected: false" })
        assertEquals(1, addonContext.notificationListeners.size)
        assertFalse(addonContext.run("discord.send", "hi").success)
    }
}
