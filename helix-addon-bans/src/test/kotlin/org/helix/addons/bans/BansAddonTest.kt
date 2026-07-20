package org.helix.addons.bans

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.storage.InMemoryAddonStorage
import org.helix.api.action.ActionResult
import org.helix.api.proxy.JoinRequest

class BansAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("bans")).apply {
        invocationResult = { invocation -> ActionResult.ok("kick for ${invocation.arguments.first()} queued") }
    }
    private val addon = BansAddon().also { it.onEnable(context) }

    @Test
    fun `ban blocks join and pardon unblocks`() {
        context.run("ban.set", "Steve", "griefing")

        val denied = context.joinGates.single().check(JoinRequest("steve"))
        assertFalse(denied.allowed)
        assertTrue(denied.message!!.contains("griefing"))

        assertTrue(context.run("ban.pardon", "STEVE").success)
        assertTrue(context.joinGates.single().check(JoinRequest("Steve")).allowed)
    }

    @Test
    fun `ban kicks online player through generic action`() {
        context.run("ban.set", "Alex", "7d", "cheating")

        val kick = context.invocations.single()
        assertEquals("player.kick", kick.action)
        assertEquals("Alex", kick.arguments.first())
        assertTrue(kick.arguments[1].contains("cheating"))
    }

    @Test
    fun `ban and pardon publish moderation notifications`() {
        context.run("ban.set", "Alex", "7d", "cheating")
        context.run("ban.pardon", "Alex")

        assertEquals(listOf("moderation", "moderation"), context.notifications.map { it.first })
        assertTrue(context.notifications[0].second.contains("[Ban]"))
        assertTrue(context.notifications[0].second.contains("cheating"))
        assertTrue(context.notifications[1].second.contains("pardoned"))
    }

    @Test
    fun `temp ban parses duration and lists with expiry`() {
        val result = context.run("ban.set", "Alex", "7d", "cheating")

        assertTrue(result.success)
        assertTrue(result.lines.first().contains("expires in 6d 23h") || result.lines.first().contains("expires in 7d"))
        assertTrue(context.run("ban.list").lines.single().contains("alex"))
        assertTrue(context.run("ban.check", "alex").lines.single().contains("cheating"))
    }

    @Test
    fun `expired temp ban no longer blocks`() {
        var now = 1_000L
        val store = BanStore(InMemoryAddonStorage(), clock = { now })
        store.set("steve", "bye", durationMs = 60_000)

        assertEquals("bye", store.activeBan("steve")?.reason)
        now = 100_000
        assertNull(store.activeBan("steve"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `store persists across instances`() {
        val storage = InMemoryAddonStorage()
        BanStore(storage).set("steve", "griefing")

        assertEquals("griefing", BanStore(storage).activeBan("Steve")?.reason)
    }

    @Test
    fun `duration tokens parse and validate`() {
        assertEquals(30L * 60_000, BanDuration.parseMillis("30m"))
        assertEquals(7L * 86_400_000, BanDuration.parseMillis("7d"))
        assertNull(BanDuration.parseMillis("perm"))
        assertTrue(BanDuration.isDurationToken("12h"))
        assertFalse(BanDuration.isDurationToken("cheating"))
        assertFailsWith<IllegalArgumentException> { BanDuration.parseMillis("7x") }
    }

    @Test
    fun `ban without duration is permanent`() {
        val result = context.run("ban.set", "Steve")

        assertTrue(result.lines.first().contains("permanent"))
    }
}
