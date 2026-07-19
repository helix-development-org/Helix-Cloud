package org.helix.addons.friends

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer

class FriendsAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("friends"))
    private val addon = FriendsAddon().also { it.onEnable(context) }

    @Test
    fun `request and accept create a friendship with notifications`() {
        context.online += OnlinePlayer("Alex")

        val requested = context.run("friend", "Steve", "add", "Alex")
        assertTrue(requested.success)
        assertEquals(
            listOf("Alex"),
            context.invocations.filter { it.action == "player.message" }.map { it.arguments.first() },
        )

        val accepted = context.run("friend", "Alex", "accept", "Steve")
        assertTrue(accepted.success)
        assertTrue(accepted.lines.first().contains("friends with Steve"))
        assertTrue(context.run("friend", "Steve", "list").lines.any { it.contains("alex") })
    }

    @Test
    fun `mutual requests auto-accept`() {
        context.run("friend", "Steve", "add", "Alex")

        val result = context.run("friend", "Alex", "add", "Steve")

        assertTrue(result.lines.first().contains("now friends"))
    }

    @Test
    fun `deny and remove work`() {
        context.run("friend", "Steve", "add", "Alex")
        assertTrue(context.run("friend", "Alex", "deny", "Steve").success)
        assertFalse(context.run("friend", "Alex", "accept", "Steve").success)

        context.run("friend", "Steve", "add", "Alex")
        context.run("friend", "Alex", "accept", "Steve")
        assertTrue(context.run("friend", "Alex", "remove", "Steve").success)
        assertTrue(context.run("friend", "Steve", "list").lines.first().contains("no friends"))
    }

    @Test
    fun `self add and duplicate requests are rejected`() {
        assertFalse(context.run("friend", "Steve", "add", "Steve").success)
        context.run("friend", "Steve", "add", "Alex")
        assertFalse(context.run("friend", "Steve", "add", "Alex").success)
    }

    @Test
    fun `join notifies online friends`() {
        context.run("friend", "Steve", "add", "Alex")
        context.run("friend", "Alex", "accept", "Steve")
        context.online += OnlinePlayer("Alex")
        context.invocations.clear()

        context.playerListeners.single().onJoin(OnlinePlayer("Steve"))

        val notified = context.invocations.single { it.action == "player.message" }
        assertEquals("alex", notified.arguments.first())
        assertTrue(notified.arguments[1].contains("Steve"))
    }

    @Test
    fun `store persists across instances`() {
        val file = createTempDirectory("friends").resolve("friends.json")
        val first = FriendStore(file)
        first.request("steve", "alex")
        first.accept("alex", "steve")

        assertTrue(FriendStore(file).areFriends("Steve", "Alex"))
    }
}
