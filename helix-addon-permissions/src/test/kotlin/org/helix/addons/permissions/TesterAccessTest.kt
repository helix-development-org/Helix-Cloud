package org.helix.addons.permissions

import org.helix.api.message.Messages
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TesterAccessTest {
    private var now = 1_000L
    private val store = PermissionStore(InMemoryAddonStorage(), clock = { now })

    /** Echoes the key and its parameters, so assertions can check which text was chosen. */
    private val messages = object : Messages {
        override fun format(key: String, vararg params: Pair<String, String>): String =
            (listOf(key) + params.map { "${it.first}=${it.second}" }).joinToString(" ")

        override fun raw(key: String): String = key
    }
    private val access = TesterAccess(store, messages, clock = { now })

    @Test
    fun `seeding creates the tester group with the test phase permissions`() {
        assertTrue(access.seedGroup())
        val group = store.group(TesterAccess.GROUP)!!
        assertEquals(10, group.weight)
        assertTrue("helix.maintenance.bypass" in group.permissions)
        assertEquals(org.helix.api.design.RankBadges.TESTER.prefix, group.prefix)
    }

    @Test
    fun `seeding never overwrites an existing group`() {
        store.saveGroup(PermissionGroup(name = "tester", weight = 3, permissions = listOf("custom")))
        assertFalse(access.seedGroup())
        assertEquals(listOf("custom"), store.group("tester")!!.permissions)
    }

    @Test
    fun `add grants the bypass permanently`() {
        access.seedGroup()
        val result = access.command("admin", listOf("add", "Steve"))
        assertTrue(result.success)
        assertTrue(store.has("steve", "helix.maintenance.bypass"))
        assertEquals(listOf("steve" to null), access.testers())
    }

    @Test
    fun `timed add expires and shows the remaining time`() {
        access.seedGroup()
        assertTrue(access.command("admin", listOf("add", "alex", "1d")).success)
        assertEquals(86_400_000L, access.testers().single().second)
        now += 86_400_001L
        assertFalse(store.has("alex", "feedback.report"))
        assertTrue(access.testers().isEmpty())
    }

    @Test
    fun `remove takes the group away and rejects non testers`() {
        access.seedGroup()
        access.command("admin", listOf("add", "steve", "7d"))
        assertTrue(access.command("admin", listOf("remove", "steve")).success)
        assertFalse(store.has("steve", "tester.tools"))
        val again = access.command("admin", listOf("remove", "steve"))
        assertFalse(again.success)
        assertTrue(again.lines.single().startsWith("tester.error.not-tester"))
    }

    @Test
    fun `invalid durations and unknown subcommands answer with help`() {
        access.seedGroup()
        assertTrue(access.command("admin", listOf("add", "steve", "forever")).lines.single().startsWith("tester.error.duration"))
        assertTrue(access.command("admin", emptyList()).lines.single().startsWith("tester.usage"))
        assertNull(access.testers().firstOrNull())
    }

    @Test
    fun `list prints a header and one line per tester`() {
        access.seedGroup()
        access.command("admin", listOf("add", "steve"))
        access.command("admin", listOf("add", "alex", "12h"))
        val lines = access.command("admin", listOf("list")).lines
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("tester.list.header count=2"))
        assertTrue(lines[1].startsWith("tester.entry.timed player=alex"))
        assertTrue(lines[2].startsWith("tester.entry.permanent player=steve"))
    }

    @Test
    fun `release gives testers the beta badge and can remove the tester group`() {
        access.seedGroup()
        access.command("mod", listOf("add", "alice"))
        access.command("mod", listOf("add", "bob", "7d"))
        val plan = access.release(dryRun = true, removeTesters = true)
        assertEquals(2, plan.deleted["tester_memberships"])
        assertNull(store.group(TesterAccess.BADGE_GROUP), "a plan changes nothing")

        access.release(dryRun = false, removeTesters = false)
        assertTrue(store.has("alice", "badge.betatester") && store.has("bob", "badge.betatester"))
        assertTrue(store.has("alice", "helix.maintenance.bypass"), "testers keep their group without the flag")

        access.release(dryRun = false, removeTesters = true)
        assertTrue(store.has("bob", "badge.betatester"))
        assertFalse(store.has("bob", "helix.maintenance.bypass"))
        assertEquals(1, store.group(TesterAccess.BADGE_GROUP)!!.weight)
    }
}
