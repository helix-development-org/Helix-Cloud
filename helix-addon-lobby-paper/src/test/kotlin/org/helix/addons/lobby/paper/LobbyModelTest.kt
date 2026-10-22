package org.helix.addons.lobby.paper

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LobbyModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the node's lobby config with a spawn decodes on the paper side`() {
        val raw = """{"lobbyTasks":["Lobby"],"spawn":{"x":0.5,"y":4.0,"z":-2.5,"yaw":90.0,"pitch":10.0},"future":1}"""

        val config = json.decodeFromString(LobbyConfig.serializer(), raw)

        assertEquals(LobbySpawn(0.5, 4.0, -2.5, 90f, 10f), config.spawn)
    }

    @Test
    fun `a config without spawn keeps the world spawn`() {
        assertNull(json.decodeFromString(LobbyConfig.serializer(), """{"lobbyTasks":["Lobby"]}""").spawn)
    }
}
