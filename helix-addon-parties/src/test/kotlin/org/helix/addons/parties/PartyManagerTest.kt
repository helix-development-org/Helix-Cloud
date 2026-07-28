package org.helix.addons.parties

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyManagerTest {
    @Test
    fun `create fails when already in a party`() {
        val manager = PartyManager()
        assertTrue(manager.create("steve") != null)
        assertNull(manager.create("Steve"))
    }

    @Test
    fun `invite requires the leader and rejects players already in a party`() {
        val manager = PartyManager()
        manager.create("steve")
        manager.create("alex")

        assertFalse(manager.invite("alex", "steve"))
        assertTrue(manager.invite("steve", "bob"))
        assertFalse(manager.invite("steve", "bob"))
        assertFalse(manager.invite("steve", "alex"))
    }

    @Test
    fun `accept joins the party and clears the invite`() {
        val manager = PartyManager()
        manager.create("steve")
        manager.invite("steve", "bob")

        assertTrue(manager.accept("bob", "steve"))
        assertEquals(listOf("steve", "bob"), manager.partyOf("bob")?.members)
        assertEquals(manager.partyOf("steve"), manager.partyOf("bob"))
        assertFalse(manager.accept("bob", "steve"))
    }

    @Test
    fun `accept without a pending invite fails`() {
        val manager = PartyManager()
        manager.create("steve")
        assertFalse(manager.accept("bob", "steve"))
    }

    @Test
    fun `accept fails when already in a party`() {
        val manager = PartyManager()
        manager.create("steve")
        manager.invite("steve", "bob")
        manager.create("bob")

        assertFalse(manager.accept("bob", "steve"))
    }

    @Test
    fun `leave transfers leadership to the next oldest member`() {
        val manager = PartyManager()
        manager.create("steve")
        manager.invite("steve", "alex")
        manager.accept("alex", "steve")
        manager.invite("steve", "bob")
        manager.accept("bob", "steve")

        assertTrue(manager.leave("steve"))

        val party = manager.partyOf("alex")
        assertEquals("alex", party?.leader)
        assertEquals(listOf("alex", "bob"), party?.members)
        assertNull(manager.partyOf("steve"))
    }

    @Test
    fun `leave as the last member dissolves the party`() {
        val manager = PartyManager()
        manager.create("steve")

        assertTrue(manager.leave("steve"))
        assertNull(manager.partyOf("steve"))
        assertFalse(manager.leave("steve"))
    }

    @Test
    fun `kick requires the leader and cannot target the leader`() {
        val manager = PartyManager()
        manager.create("steve")
        manager.invite("steve", "alex")
        manager.accept("alex", "steve")

        assertFalse(manager.kick("alex", "steve"))
        assertFalse(manager.kick("steve", "steve"))
        assertTrue(manager.kick("steve", "alex"))
        assertNull(manager.partyOf("alex"))
        assertEquals(listOf("steve"), manager.partyOf("steve")?.members)
    }

    @Test
    fun `kick on a non-member fails`() {
        val manager = PartyManager()
        manager.create("steve")
        assertFalse(manager.kick("steve", "alex"))
    }

    @Test
    fun `pendingInvites lists outstanding invites for the leader`() {
        val manager = PartyManager()
        manager.create("steve")
        manager.invite("steve", "alex")
        manager.invite("steve", "bob")

        assertEquals(setOf("alex", "bob"), manager.pendingInvites("steve"))

        manager.accept("alex", "steve")
        assertEquals(setOf("bob"), manager.pendingInvites("steve"))
    }
}
