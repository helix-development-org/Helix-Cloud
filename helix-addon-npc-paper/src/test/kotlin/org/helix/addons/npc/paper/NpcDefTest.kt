package org.helix.addons.npc.paper

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpcDefTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips through the node contract`() {
        val def = NpcDef(
            id = "greeter",
            task = "lobby",
            world = "world",
            x = 1.5,
            y = 64.0,
            z = -2.5,
            yaw = 90f,
            pitch = 10f,
            skin = "Notch",
            hologramLines = listOf("<gold>Hi", "<gray>click"),
            lookMode = "nearest",
            interactAction = "server Lobby",
        )
        assertEquals(def, json.decodeFromString<NpcDef>(json.encodeToString(def)))
    }

    @Test
    fun `applies node defaults for a minimal payload`() {
        val def = json.decodeFromString<NpcDef>("""{"id":"m","world":"world","x":0.0,"y":0.0,"z":0.0}""")
        assertEquals("*", def.task)
        assertEquals("self", def.skin)
        assertEquals("none", def.lookMode)
        assertNull(def.interactAction)
    }
}
