package org.helix.addons.bettermsgs

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer

class BetterMsgsAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("bettermsgs"))
    private val addon = BetterMsgsAddon().also { it.onEnable(context) }
    private val json = Json

    private fun send(from: String, to: String, text: String) =
        context.run("bettermsgs.send", from, to, text)

    private fun history(a: String, b: String, offset: Int, limit: Int): HistoryResponse =
        json.decodeFromString(
            context.run("bettermsgs.history", a, b, offset.toString(), limit.toString()).lines.single(),
        )

    private fun contacts(player: String): List<ContactView> =
        json.decodeFromString(context.run("bettermsgs.contacts", player).lines.single())

    private fun notifications() = context.invocations.filter { it.action == "player.message" }

    @Test
    fun `send appends the message, bumps both contact indexes and returns the timestamp`() {
        val before = System.currentTimeMillis()
        val result = send("Steve", "Alex", "hello there")
        assertTrue(result.success)

        val response = json.decodeFromString<SendResponse>(result.lines.single())
        assertTrue(response.ok)
        assertTrue(response.epochMs >= before)

        val window = history("alex", "STEVE", 0, 50)
        assertEquals(1, window.total)
        assertEquals(ChatMessage("steve", "hello there", response.epochMs), window.messages.single())

        val alexContacts = contacts("Alex")
        assertEquals(listOf(ContactView("steve", response.epochMs, 1, false)), alexContacts)
        assertEquals(listOf(ContactView("alex", response.epochMs, 0, false)), contacts("Steve"))
    }

    @Test
    fun `conversation caps at the newest 500 messages`() {
        repeat(510) { index -> send("Steve", "Alex", "m${index + 1}") }

        val newest = history("steve", "alex", 0, 1)
        assertEquals(500, newest.total)
        assertEquals("m510", newest.messages.single().text)

        val oldest = history("steve", "alex", 499, 1)
        assertEquals("m11", oldest.messages.single().text)
    }

    @Test
    fun `history windows from the end, oldest to newest inside the window`() {
        (1..5).forEach { index -> send("Steve", "Alex", "m$index") }

        val newestWindow = history("steve", "alex", 0, 2)
        assertEquals(5, newestWindow.total)
        assertEquals(0, newestWindow.offset)
        assertEquals(listOf("m4", "m5"), newestWindow.messages.map { it.text })

        assertEquals(listOf("m2", "m3"), history("steve", "alex", 2, 2).messages.map { it.text })
        assertEquals(listOf("m1"), history("steve", "alex", 4, 2).messages.map { it.text })
        assertTrue(history("steve", "alex", 10, 2).messages.isEmpty())
    }

    @Test
    fun `contacts are sorted by recency and carry unread and online state`() {
        context.online += OnlinePlayer("Bob")
        send("Steve", "Alex", "first")
        Thread.sleep(5)
        send("Bob", "Alex", "second")
        send("Bob", "Alex", "third")

        val entries = contacts("Alex")
        assertEquals(listOf("bob", "steve"), entries.map { it.name })
        assertEquals(listOf(2, 1), entries.map { it.unread })
        assertEquals(listOf(true, false), entries.map { it.online })
    }

    @Test
    fun `read resets the unread counter`() {
        send("Steve", "Alex", "hi")
        assertEquals(1, contacts("Alex").single().unread)

        val result = context.run("bettermsgs.read", "Alex", "Steve")
        assertTrue(result.success)
        assertEquals("""{"ok":true}""", result.lines.single())
        assertEquals(0, contacts("Alex").single().unread)
    }

    @Test
    fun `unfocused online recipient is notified via player-message with the click template`() {
        context.online += OnlinePlayer("Alex")

        send("Steve", "Alex", "hi")

        val notification = notifications().single()
        assertEquals("Alex", notification.arguments.first())
        assertTrue(notification.arguments[1].contains("Steve"))
        assertTrue(notification.arguments[1].contains("<click:run_command:'/msg Steve'>"))
    }

    @Test
    fun `offline recipient is not notified`() {
        send("Steve", "Alex", "hi")

        assertTrue(notifications().isEmpty())
    }

    @Test
    fun `focus suppresses the notification and resets unread`() {
        context.online += OnlinePlayer("Alex")
        send("Steve", "Alex", "hi")
        assertEquals(1, contacts("Alex").single().unread)

        val focused = context.run("bettermsgs.focus", "Alex", "Steve")
        assertTrue(focused.success)
        assertEquals("""{"ok":true}""", focused.lines.single())
        assertEquals(0, contacts("Alex").single().unread)

        context.invocations.clear()
        send("Steve", "Alex", "again")
        assertTrue(notifications().isEmpty())

        context.run("bettermsgs.focus", "Alex", "-")
        send("Steve", "Alex", "after clearing")
        assertEquals(1, notifications().size)
    }

    @Test
    fun `focus on another peer does not suppress the notification`() {
        context.online += OnlinePlayer("Alex")
        context.run("bettermsgs.focus", "Alex", "Bob")

        send("Steve", "Alex", "hi")

        assertEquals(1, notifications().size)
    }

    @Test
    fun `self messages and blank texts are rejected`() {
        val self = send("Steve", "steve", "hi")
        assertFalse(self.success)
        assertTrue(self.lines.single().contains("yourself"))

        assertFalse(send("Steve", "Alex", " ").success)
        assertFalse(context.run("bettermsgs.send", "Steve", "Alex").success)
    }

    @Test
    fun `store persists across instances`() {
        val storage = org.helix.api.storage.InMemoryAddonStorage()
        val first = MessageStore(storage)
        first.append("Steve", "Alex", "hi", 123L)

        val second = MessageStore(storage)
        assertEquals(listOf(ChatMessage("steve", "hi", 123L)), second.history("alex", "steve"))
        assertEquals(mapOf("steve" to ContactEntry(123L, 1)), second.contacts("Alex"))
    }
}
