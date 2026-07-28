package org.helix.addons.subtitles.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleValuesTest {
    @Test
    fun `extracts only subtitle text entries, stripping the prefix`() {
        val bridgeValues = mapOf(
            "subtitle.text.steve" to "Veteran",
            "subtitle.text.alex" to "Legend",
            "tablist.header" to "Welcome",
            "chat.format" to "{name}: {message}",
        )

        assertEquals(mapOf("steve" to "Veteran", "alex" to "Legend"), SubtitleValues.parse(bridgeValues))
    }

    @Test
    fun `no subtitle entries yields an empty map`() {
        assertEquals(emptyMap(), SubtitleValues.parse(mapOf("tablist.header" to "Welcome")))
    }
}
