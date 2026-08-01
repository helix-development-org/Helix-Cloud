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
        context.run("ban.set", "Steve", "Mod", "griefing")

        val denied = context.joinGates.single().check(JoinRequest("steve"))
        assertFalse(denied.allowed)
        assertTrue(denied.message!!.contains("griefing"))

        assertTrue(context.run("ban.pardon", "STEVE", "Mod").success)
        assertTrue(context.joinGates.single().check(JoinRequest("Steve")).allowed)
    }

    @Test
    fun `ban alts lists sharing accounts and marks banned ones`() {
        context.recordJoin("Steve", "uuid-1")
        context.recordJoin("Alex", "uuid-2")
        context.recordJoin("Mallory", "uuid-3")
        context.sharedAddresses["uuid-1"] = listOf("uuid-2", "uuid-3")
        context.run("ban.set", "Mallory", "Mod", "griefing")

        val result = context.run("ban.alts", "Steve")

        assertTrue(result.success)
        assertTrue(result.lines.any { it.startsWith("alex (uuid-2)") && !it.contains("BANNED") })
        assertTrue(result.lines.any { it.startsWith("mallory (uuid-3)") && it.contains("[BANNED]") })

        assertFalse(context.run("ban.alts", "Nobody").success)
        assertTrue(context.run("ban.alts", "Alex").lines.single().contains("no accounts share"))
    }

    @Test
    fun `ban kicks online player through generic action`() {
        context.run("ban.set", "Alex", "Mod", "7d", "cheating")

        val kick = context.invocations.single()
        assertEquals("player.kick", kick.action)
        assertEquals("Alex", kick.arguments.first())
        assertTrue(kick.arguments[1].contains("cheating"))
    }

    @Test
    fun `ban and pardon publish moderation notifications`() {
        context.run("ban.set", "Alex", "Mod", "7d", "cheating")
        context.run("ban.pardon", "Alex", "Mod")

        assertEquals(listOf("moderation", "moderation"), context.notifications.map { it.first })
        assertTrue(context.notifications[0].second.contains("was banned"))
        assertTrue(context.notifications[0].second.contains("cheating"))
        assertTrue(context.notifications[1].second.contains("pardoned"))
    }

    @Test
    fun `temp ban parses duration and lists with expiry`() {
        val result = context.run("ban.set", "Alex", "Mod", "7d", "cheating")

        assertTrue(result.success)
        assertTrue(result.lines.first().contains("expires in 6d 23h") || result.lines.first().contains("expires in 7d"))
        assertTrue(context.run("ban.list").lines.single().contains("alex"))
        assertTrue(context.run("ban.check", "alex").lines.single().contains("cheating"))
    }

    @Test
    fun `ban records issuedBy and history survives a pardon`() {
        context.run("ban.set", "Alex", "Mod", "cheating")

        assertTrue(context.run("ban.check", "Alex").lines.single().contains("by Mod"))

        context.run("ban.pardon", "Alex", "Mod")

        val history = context.run("ban.history", "Alex")
        assertTrue(history.success)
        assertTrue(history.lines.single().contains("issued by Mod"))
        assertTrue(history.lines.single().contains("pardoned by Mod"))
    }

    @Test
    fun `expired ban moves to history instead of disappearing`() {
        var now = 1_000L
        val backingStore = BanStore(InMemoryAddonStorage(), clock = { now })
        backingStore.set("steve", "bye", durationMs = 60_000, issuedBy = "Mod")

        now = 100_000
        assertNull(backingStore.activeBan("steve"))

        val history = backingStore.historyOf("steve")
        assertEquals(1, history.size)
        assertEquals("Mod", history.single().issuedBy)
        assertNull(history.single().revokedBy)
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
    fun `a ban survives a rename because the join gate checks the joining account's uuid`() {
        context.recordJoin("Steve", "uuid-1")
        context.run("ban.set", "Steve", "Mod", "griefing")

        // Steve renamed to Steve2 in Mojang's records, but their uuid never changes — the
        // bridge reports the real uuid at login, which is what the ban must be keyed on.
        val denied = context.joinGates.single().check(JoinRequest("Steve2", "uuid-1"))

        assertFalse(denied.allowed)
        assertTrue(denied.message!!.contains("griefing"))
    }

    @Test
    fun `a ban set for a player never seen falls back to their name, then migrates on first join`() {
        context.run("ban.set", "Offline", "Mod", "banned while offline")

        // not yet seen by this node: the join gate still enforces the name-keyed fallback
        assertFalse(context.joinGates.single().check(JoinRequest("Offline", "uuid-9")).allowed)

        // renaming again afterwards still doesn't help, now that the ban carried forward to the uuid
        assertFalse(context.joinGates.single().check(JoinRequest("Renamed", "uuid-9")).allowed)
    }

    @Test
    fun `a pardon by name still finds a ban already migrated to uuid`() {
        context.recordJoin("Steve", "uuid-1")
        context.run("ban.set", "Steve", "Mod", "griefing")

        assertTrue(context.run("ban.pardon", "steve").success)
        assertTrue(context.joinGates.single().check(JoinRequest("Steve", "uuid-1")).allowed)
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

    @Test
    fun `player-data provider exports and pardons an active ban`() {
        val provider = context.playerDataProviders.single()
        assertNull(provider.export("steve"))

        context.run("ban.set", "Steve", "griefing")

        assertTrue(provider.export("steve")!!.contains("griefing"))
        assertTrue(provider.delete("steve"))
        assertTrue(context.joinGates.single().check(JoinRequest("Steve")).allowed)
        assertFalse(provider.delete("steve"))
    }
}
