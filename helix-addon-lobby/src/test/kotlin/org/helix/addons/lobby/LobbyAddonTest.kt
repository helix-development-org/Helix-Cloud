package org.helix.addons.lobby

import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LobbyAddonTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val directory = createTempDirectory("lobby")
    private val context = org.helix.addon.sdk.testing.RecordingAddonContext(directory)
    private val addon = LobbyAddon().also { it.onEnable(context) }

    private fun published(): LobbyConfig =
        json.decodeFromString(LobbyConfig.serializer(), context.bridgeValues.getValue("lobby.config"))

    @Test
    fun `enable publishes a config`() {
        assertTrue(context.bridgeValues.containsKey("lobby.config"))
        // the default ships a starter layout under the wildcard key
        assertTrue(published().layouts.containsKey("*"))
    }

    @Test
    fun `set stores lobby tasks and republishes`() {
        val payload = """{"lobbyTasks":["Lobby","Lounge"],"layouts":{"*":{"items":[]}}}"""

        assertTrue(context.run("lobby.set", payload).success)

        val config = published()
        assertEquals(listOf("Lobby", "Lounge"), config.lobbyTasks)
        assertTrue(config.isLobbyTask("Lobby"))
        assertFalse(config.isLobbyTask("BedWars"))
    }

    @Test
    fun `set sanitizes duplicate tasks and out-of-range slots`() {
        val payload = """
            {"lobbyTasks":["Lobby"," Lobby ",""],
             "layouts":{"Lobby":{"items":[
               {"slot":3,"material":"COMPASS","action":"OPEN_SERVER_MENU"},
               {"slot":20,"material":"PAPER","action":"RUN_COMMAND"}]}}}
        """.trimIndent()

        assertTrue(context.run("lobby.set", payload).success)

        val config = published()
        assertEquals(listOf("Lobby"), config.lobbyTasks)
        val items = config.layoutFor("Lobby").items
        assertEquals(1, items.size)
        assertEquals(3, items.first().slot)
    }

    @Test
    fun `set rejects invalid json`() {
        assertFalse(context.run("lobby.set", "{not json").success)
    }

    @Test
    fun `get returns the current config as json`() {
        context.run("lobby.set", """{"lobbyTasks":["Lobby"]}""")

        val exported = context.run("lobby.get")
        assertTrue(exported.success)
        val config = json.decodeFromString(LobbyConfig.serializer(), exported.lines.first())
        assertEquals(listOf("Lobby"), config.lobbyTasks)
    }

    @Test
    fun `navigator modes keep pickInstance and old configs default to false`() {
        val payload = """{"serverMenu":{"modes":[{"task":"CityBuild","place":"kronstadt","emblem":"crown","pickInstance":true},{"task":"MineClash-Game"}]}}"""

        assertTrue(context.run("lobby.set", payload).success)

        val modes = published().serverMenu.modes
        assertTrue(modes.first { it.task == "CityBuild" }.pickInstance)
        assertFalse(modes.first { it.task == "MineClash-Game" }.pickInstance)
    }

    @Test
    fun `spawn set needs the admin permission and is published`() {
        val spawn = """{"x":0.5,"y":4.0,"z":-2.5,"yaw":90.0,"pitch":0.0}"""
        assertFalse(context.run("lobby.spawn.set", "Guest", spawn).success)

        context.permissionCheck = { player, node -> player == "Tytoss" && node == LobbyAddon.ADMIN_PERMISSION }
        assertTrue(context.run("lobby.spawn.set", "Tytoss", spawn).success)

        assertEquals(LobbySpawn(0.5, 4.0, -2.5, 90f, 0f), published().spawn)
    }

    @Test
    fun `invalid spawns are rejected`() {
        context.permissionCheck = { _, _ -> true }
        assertFalse(context.run("lobby.spawn.set", "Tytoss", """{"x":0,"y":900,"z":0}""").success)
        assertFalse(context.run("lobby.spawn.set", "Tytoss", "{nope").success)
        assertEquals(null, published().spawn)
    }

    @Test
    fun `a panel save without spawn keeps it and clear removes it`() {
        context.permissionCheck = { _, _ -> true }
        assertTrue(context.run("lobby.spawn.set", "Tytoss", """{"x":1,"y":5,"z":1}""").success)

        assertTrue(context.run("lobby.set", """{"lobbyTasks":["Lobby"]}""").success)
        assertEquals(LobbySpawn(1.0, 5.0, 1.0), published().spawn)

        assertTrue(context.run("lobby.spawn.clear").success)
        assertEquals(null, published().spawn)
    }
}
