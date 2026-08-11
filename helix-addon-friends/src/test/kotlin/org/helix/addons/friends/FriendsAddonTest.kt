package org.helix.addons.friends

import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.player.OnlinePlayer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertTrue(accepted.lines.first().contains("now friends"))
        assertTrue(accepted.lines.first().contains("Steve"))
        assertTrue(context.run("friend", "Steve", "list").lines.any { it.contains("alex") })
    }

    @Test
    fun `mutual requests auto-accept`() {
        context.run("friend", "Steve", "add", "Alex")

        val result = context.run("friend", "Alex", "add", "Steve")

        assertTrue(result.lines.first().contains("now friends"))
    }

    @Test
    fun `deny works and an immediate re-request is rate-limited`() {
        context.run("friend", "Steve", "add", "Alex")
        assertTrue(context.run("friend", "Alex", "deny", "Steve").success)
        assertFalse(context.run("friend", "Alex", "accept", "Steve").success)

        // Re-requesting the same target right after a denial is blocked by the anti-harassment
        // cooldown (see FriendStore) — otherwise a sender could ping a victim on every deny.
        assertFalse(context.run("friend", "Steve", "add", "Alex").success)
    }

    @Test
    fun `remove ends an existing friendship`() {
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
        val storage = org.helix.api.storage.InMemoryAddonStorage()
        val first = FriendStore(storage)
        first.request("steve", "alex")
        first.accept("alex", "steve")

        assertTrue(FriendStore(storage).areFriends("Steve", "Alex"))
    }

    @Test
    fun `a friendship survives a rename because it is keyed on uuid once known`() {
        context.recordJoin("Steve", "uuid-1")
        context.recordJoin("Alex", "uuid-2")
        context.run("friend", "Steve", "add", "Alex")
        context.run("friend", "Alex", "accept", "Steve")

        // Steve renamed; the node still resolves "steve2" -> uuid-1, so the friendship holds
        context.recordJoin("Steve2", "uuid-1")

        assertTrue(context.run("friend", "Steve2", "list").lines.any { it.contains("alex") })
        assertTrue(context.run("friend", "Alex", "list").lines.any { it.contains("steve2") })
    }

    @Test
    fun `a freed name reused by someone else is not automatically friends with anyone`() {
        context.recordJoin("Steve", "uuid-1")
        context.recordJoin("Alex", "uuid-2")
        context.run("friend", "Steve", "add", "Alex")
        context.run("friend", "Alex", "accept", "Steve")
        // Steve renames away, freeing the name
        context.recordJoin("Steve2", "uuid-1")

        // a new, unrelated account takes the freed name "steve"
        context.recordJoin("Steve", "uuid-3")

        assertTrue(context.run("friend", "Steve", "list").lines.first().contains("no friends"))
    }

    @Test
    fun `request cooldown blocks a fast re-request but clears after it elapses`() {
        var now = 0L
        val store = FriendStore(org.helix.api.storage.InMemoryAddonStorage(), cooldownMillis = 10_000, clock = { now })

        assertEquals(FriendRequestOutcome.SENT, store.request("steve", "alex"))
        store.deny("alex", "steve")

        now = 5_000
        assertEquals(FriendRequestOutcome.COOLDOWN, store.request("steve", "alex"))

        now = 10_001
        assertEquals(FriendRequestOutcome.SENT, store.request("steve", "alex"))
    }

    @Test
    fun `player-data provider exports friends and requests, delete forgets both`() {
        context.run("friend", "Steve", "add", "Alex")
        context.run("friend", "Alex", "accept", "Steve")
        context.run("friend", "Steve", "add", "Bob")
        val provider = context.playerDataProviders.single()

        val export = provider.export("bob")!!
        assertTrue(export.contains("steve"))

        assertTrue(provider.delete("steve"))
        assertFalse(FriendStore(context.storage).areFriends("Steve", "Alex"))
        assertEquals(null, provider.export("steve"))
        assertFalse(provider.delete("steve"))
    }
}
