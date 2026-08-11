package org.helix.bridge.paper

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TablistDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses the tablist addon bridge value format`() {
        val raw = """
            {"header":"&6A","footer":"&7x","headerFrames":["&6A","&6B"],"footerFrames":["&7x"],"intervalMs":1000}
        """.trimIndent()

        val data = json.decodeFromString<TablistData>(raw)

        assertEquals(2, data.frameCount())
        assertEquals("&6A", data.headerAt(0))
        assertEquals("&6B", data.headerAt(1))
        // shorter frame list wraps around
        assertEquals("&7x", data.footerAt(1))
    }

    @Test
    fun `frame index rotates time-based`() {
        val data = TablistData(headerFrames = listOf("a", "b", "c"), intervalMs = 1000)

        assertEquals(0, data.frameIndexAt(0))
        assertEquals(1, data.frameIndexAt(1000))
        assertEquals(2, data.frameIndexAt(2999))
        assertEquals(0, data.frameIndexAt(3000))
    }

    @Test
    fun `single frame config never animates and unescapes line breaks`() {
        val data = TablistData(header = "top\\nbottom")

        assertEquals(1, data.frameCount())
        assertEquals("top\nbottom", data.headerAt(0))
        assertEquals(0, data.frameIndexAt(987_654_321))
    }
}
