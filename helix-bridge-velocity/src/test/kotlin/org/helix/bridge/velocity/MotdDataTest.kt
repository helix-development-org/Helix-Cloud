package org.helix.bridge.velocity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class MotdDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses the motd addon bridge value format`() {
        val raw = """
            {
              "normal": {
                "line1": "<gradient:#8b5cf6:#38bdf8><bold>{network}</bold></gradient>",
                "line2": "<gray>{online}/{max} online",
                "maxPlayers": -1,
                "onlinePlayers": -1,
                "versionText": "",
                "hover": ["&bHelix", "&7join us"]
              },
              "maintenance": {
                "line1": "&cMaintenance",
                "line2": "",
                "maxPlayers": 0,
                "onlinePlayers": 0,
                "versionText": "&cMaintenance",
                "hover": []
              }
            }
        """.trimIndent()

        val data = json.decodeFromString<MotdData>(raw)

        assertEquals(-1, data.normal.maxPlayers)
        assertEquals(listOf("&bHelix", "&7join us"), data.normal.hover)
        assertEquals("&cMaintenance", data.maintenance.versionText)
        assertEquals(0, data.maintenance.onlinePlayers)
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val data = json.decodeFromString<MotdData>("""{"normal":{"line1":"hi"}}""")

        assertEquals("hi", data.normal.line1)
        assertEquals(-1, data.normal.onlinePlayers)
        assertEquals("", data.maintenance.line1)
    }
}
