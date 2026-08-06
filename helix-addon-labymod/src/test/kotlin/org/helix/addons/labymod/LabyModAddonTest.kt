package org.helix.addons.labymod

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer

class LabyModAddonTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val context = RecordingAddonContext(createTempDirectory("labymod"))
    private val addon = LabyModAddon().also { it.onEnable(context) }

    private fun queue(): LabyCommandQueue =
        json.decodeFromString(context.bridgeValues.getValue("labymod.cmd"))

    @Test
    fun `reports feed presence stats and lifetime users`() {
        context.online += OnlinePlayer("Steve", uuid = "uuid-1")
        context.online += OnlinePlayer("Alex", uuid = "uuid-2")

        assertTrue(context.run("labymod.report", "Steve", "uuid-1", "4.2.3").success)
        assertTrue(context.run("labymod.report", "Gone", "uuid-9", "4.0.0").success)

        assertEquals(listOf("Steve (4.2.3)"), context.run("labymod.list").lines)
        val stats = context.run("labymod.stats").lines
        assertTrue(stats[0].contains("1/2"))
        assertTrue(stats[1].contains("2"))

        // leave drops the presence but not the lifetime counter
        context.playerListeners.forEach { it.onLeave(OnlinePlayer("Steve", uuid = "uuid-1")) }
        assertEquals("no LabyMod users online", context.run("labymod.list").lines.single())
        assertTrue(context.run("labymod.stats").lines[1].contains("2"))
    }

    @Test
    fun `config bridge value is published and config set updates flags`() {
        val initial = json.decodeFromString<LabyConfig>(context.bridgeValues.getValue("labymod.config"))
        assertTrue(initial.economyHud)
        assertEquals(LabyConfig.DEFAULT_MENU, initial.menuEntries)

        assertTrue(context.run("labymod.config.set", "economy=false", "rpcformat={network} | {service}").success)

        val updated = json.decodeFromString<LabyConfig>(context.bridgeValues.getValue("labymod.config"))
        assertFalse(updated.economyHud)
        assertEquals("{network} | {service}", updated.rpcFormat)
        assertEquals(updated, LabyConfig.load(context.storage))
    }

    @Test
    fun `menu entries are managed by index`() {
        assertTrue(context.run("labymod.menu.add", "/spawn", "Zum", "Spawn").success)
        assertTrue(context.run("labymod.menu.list").lines.any { it.contains("Zum Spawn -> /spawn") })

        assertTrue(context.run("labymod.menu.remove", "5").success)
        assertFalse(context.run("labymod.menu.list").lines.any { it.contains("/spawn") })
        assertFalse(context.run("labymod.menu.remove", "99").success)
    }

    @Test
    fun `npc entity reports publish the map and clicks enqueue interact emotes`() {
        assertTrue(context.run("labymod.npc.set", "greeter", "30", "2,3", "1").success)
        assertTrue(context.run("labymod.npc.entity", "Lobby-1", "Greeter", "11111111-1111-1111-1111-111111111111").success)

        val npcs = json.decodeFromString<Map<String, Map<String, String>>>(
            context.bridgeValues.getValue("labymod.npcs"),
        )
        assertEquals("11111111-1111-1111-1111-111111111111", npcs.getValue("Lobby-1").getValue("greeter"))

        assertTrue(context.run("labymod.npc.clicked", "Lobby-1", "Greeter", "Steve").success)
        val command = queue().entries.single()
        assertEquals("emote", command.type)
        assertEquals("Lobby-1", command.service)
        assertEquals(listOf("11111111-1111-1111-1111-111111111111", "1"), command.args)

        // clicks without configured interact emotes enqueue nothing
        assertTrue(context.run("labymod.npc.clicked", "Lobby-1", "Unknown", "Steve").success)
        assertEquals(1, queue().entries.size)
    }

    @Test
    fun `gameplay actions enqueue sequenced commands`() {
        assertTrue(context.run("labymod.marker", "Steve", "10", "64", "-20", "Treffpunkt").success)
        assertTrue(context.run("labymod.banner", "all", "https://example.org/banner.png").success)
        assertTrue(context.run("labymod.prompt.server", "Steve", "mc.example.org", "Komm", "vorbei").success)
        assertFalse(context.run("labymod.marker", "Steve", "10", "64").success)

        val entries = queue().entries
        assertEquals(listOf("marker", "banner", "serverswitch"), entries.map { it.type })
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.seq })
        assertEquals(listOf("10", "64", "-20", "Treffpunkt"), entries[0].args)
    }

    @Test
    fun `emote action targets every service the npc is spawned on`() {
        context.run("labymod.npc.entity", "Lobby-1", "greeter", "11111111-1111-1111-1111-111111111111")
        context.run("labymod.npc.entity", "Lobby-2", "greeter", "22222222-2222-2222-2222-222222222222")

        assertFalse(context.run("labymod.emote", "other", "5").success)
        assertTrue(context.run("labymod.emote", "greeter", "5").success)
        assertEquals(setOf("Lobby-1", "Lobby-2"), queue().entries.map { it.service }.toSet())
    }

    @Test
    fun `prompt responses become labymod notifications`() {
        assertTrue(context.run("labymod.prompt.response", "Steve", "mein", "Vorschlag").success)

        val notification = context.notifications.single()
        assertEquals("labymod", notification.first)
        assertTrue(notification.second.contains("Steve"))
        assertTrue(notification.second.contains("mein Vorschlag"))
    }

    @Test
    fun `report actions are bridge invocable`() {
        listOf("labymod.report", "labymod.npc.entity", "labymod.npc.clicked", "labymod.prompt.response")
            .forEach { name ->
                assertTrue(context.handlers.getValue(name).first.bridgeInvocable, "$name must be bridgeInvocable")
            }
        assertFalse(context.handlers.getValue("labymod.config.set").first.bridgeInvocable)
    }
}
