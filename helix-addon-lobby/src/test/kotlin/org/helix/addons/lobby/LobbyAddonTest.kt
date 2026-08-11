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
}
