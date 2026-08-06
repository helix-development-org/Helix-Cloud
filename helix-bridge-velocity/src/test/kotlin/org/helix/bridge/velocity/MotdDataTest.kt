package org.helix.bridge.velocity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun `frames rotate time-based and fall back to base lines`() {
        val animated = MotdProfileData(
            frames = listOf(MotdFrameData("a", "1"), MotdFrameData("b", "2")),
            frameIntervalMs = 1000,
        )
        assertEquals("a", animated.frameAt(0).line1)
        assertEquals("b", animated.frameAt(1000).line1)
        assertEquals("a", animated.frameAt(2000).line1)

        val static = MotdProfileData(line1 = "solo", line2 = "line")
        assertEquals("solo", static.frameAt(123_456).line1)
    }
}
