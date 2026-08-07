package org.helix.addons.scoreboard

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreboardAddonTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), BoardConfig.serializer())
    private val directory = createTempDirectory("scoreboard")
    private val context = org.helix.addon.sdk.testing.RecordingAddonContext(directory)
    private val addon = ScoreboardAddon().also { it.onEnable(context) }

    private fun publishedBoards(): Map<String, BoardConfig> =
        json.decodeFromString(mapSerializer, context.bridgeValues.getValue("scoreboard.config"))

    @Test
    fun `enable publishes the default board`() {
        val boards = publishedBoards()
        assertTrue(boards.containsKey("default"))
        assertTrue(boards.getValue("default").enabled)
    }

    @Test
    fun `set replaces a task board and republishes`() {
        val payload = """{"title":"&aLobby","lines":["&fone","&ftwo"],"enabled":true}"""

        assertTrue(context.run("scoreboard.set", "Lobby", payload).success)

        val boards = publishedBoards()
        assertEquals("&aLobby", boards.getValue("Lobby").title)
        assertEquals(listOf("&fone", "&ftwo"), boards.getValue("Lobby").lines)
    }

    @Test
    fun `set rejects invalid json and more than 15 lines`() {
        assertFalse(context.run("scoreboard.set", "Lobby", "{not json").success)
        val lines = (1..16).joinToString(",") { "\"l$it\"" }
        assertFalse(context.run("scoreboard.set", "Lobby", """{"lines":[$lines]}""").success)
        // 15 lines is exactly the limit and must pass
        val ok = (1..15).joinToString(",") { "\"l$it\"" }
        assertTrue(context.run("scoreboard.set", "Lobby", """{"lines":[$ok]}""").success)
    }

    @Test
    fun `setline edits and appends within the limit`() {
        context.run("scoreboard.set", "Lobby", """{"title":"t","lines":["a","b"]}""")

        assertTrue(context.run("scoreboard.setline", "Lobby", "0", "&cnew").success)
        assertEquals("&cnew", publishedBoards().getValue("Lobby").lines[0])

        assertTrue(context.run("scoreboard.setline", "Lobby", "2", "&9added").success)
        assertEquals(3, publishedBoards().getValue("Lobby").lines.size)

        assertFalse(context.run("scoreboard.setline", "Lobby", "9", "gap").success)
    }

    @Test
    fun `toggle flips enabled and republishes`() {
        context.run("scoreboard.set", "Lobby", """{"title":"t","lines":["a"]}""")

        assertTrue(context.run("scoreboard.toggle", "Lobby", "off").success)
        assertFalse(publishedBoards().getValue("Lobby").enabled)

        assertTrue(context.run("scoreboard.toggle", "Lobby", "on").success)
        assertTrue(publishedBoards().getValue("Lobby").enabled)

        assertFalse(context.run("scoreboard.toggle", "Lobby", "maybe").success)
    }

    @Test
    fun `reset removes an override but restores the default`() {
        context.run("scoreboard.set", "Lobby", """{"title":"t","lines":["a"]}""")
        assertTrue(publishedBoards().containsKey("Lobby"))

        assertTrue(context.run("scoreboard.reset", "Lobby").success)
        assertFalse(publishedBoards().containsKey("Lobby"))

        // resetting the default restores it rather than deleting it
        context.run("scoreboard.set", "default", """{"title":"custom","lines":[]}""")
        assertEquals("custom", publishedBoards().getValue("default").title)
        assertTrue(context.run("scoreboard.reset", "default").success)
        assertEquals(BoardConfig().title, publishedBoards().getValue("default").title)
    }

    @Test
    fun `boards persist across a reload and default falls back`() {
        context.run("scoreboard.set", "Lobby", """{"title":"kept","lines":["a"]}""")

        val second = org.helix.addon.sdk.testing.RecordingAddonContext(directory, context.storage)
        ScoreboardAddon().onEnable(second)

        val reloaded = json.decodeFromString(mapSerializer, second.bridgeValues.getValue("scoreboard.config"))
        assertEquals("kept", reloaded.getValue("Lobby").title)
        // a task without its own board is served by the default entry
        assertTrue(reloaded.containsKey("default"))
    }

    @Test
    fun `get returns the whole map as json`() {
        val result = context.run("scoreboard.get")
        assertTrue(result.success)
        val boards = json.decodeFromString(mapSerializer, result.lines.first())
        assertTrue(boards.containsKey("default"))
    }
}
