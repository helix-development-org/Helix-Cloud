package org.helix.addons.mineclash

import org.helix.addons.mineclash.queue.Matchmaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchmakerTest {
    private var now = 1_000_000L
    private val matchmaker = Matchmaker(clock = { now }, timeoutMillis = 60_000, serviceTtlMillis = 10_000)

    @Test
    fun `hosts a 1v1 on the first player's service and moves the other`() {
        matchmaker.poll("Game-1")
        matchmaker.poll("Game-2")
        assertNull(matchmaker.join("a", "Alice", "1V1", "Game-1"))
        assertEquals("already queued", matchmaker.join("a", "Alice", "1V1", "Game-1"))
        assertNull(matchmaker.join("b", "Bob", "1v1", "Game-2"))

        val host = matchmaker.poll("Game-1")
        assertEquals(1, host.matches.size)
        assertEquals(listOf(listOf("a"), listOf("b")), host.matches.single().teams)
        assertEquals("Bob", host.matches.single().names["b"])
        assertTrue(host.connect.isEmpty())

        val other = matchmaker.poll("Game-2")
        assertTrue(other.matches.isEmpty())
        assertEquals(listOf("b" to "Game-1"), other.connect.map { it.uuid to it.target })
        assertTrue(matchmaker.poll("Game-1").matches.isEmpty(), "assignments are delivered once")
        assertTrue(matchmaker.queued().isEmpty())
    }

    @Test
    fun `waits for enough players and honours leave and timeout`() {
        assertNull(matchmaker.join("a", "Alice", "2V2", "Game-1"))
        assertNull(matchmaker.join("b", "Bob", "2V2", "Game-1"))
        assertNull(matchmaker.join("c", "Cid", "2V2", "Game-1"))
        assertTrue(matchmaker.poll("Game-1").matches.isEmpty())
        assertTrue(matchmaker.leave("c"))
        assertTrue(!matchmaker.leave("c"))
        assertEquals(mapOf("2V2" to 2), matchmaker.queued())
        now += 61_000
        matchmaker.poll("Game-1")
        assertTrue(matchmaker.queued().isEmpty(), "expired entries are dropped")
        assertEquals("unknown mode 'ffa'", matchmaker.join("d", "Dan", "ffa", "Game-1"))
    }

    @Test
    fun `falls back to a live service when the first player's service died`() {
        matchmaker.poll("Game-2")
        assertNull(matchmaker.join("a", "Alice", "1V1", "Game-1"))
        now += 20_000
        assertNull(matchmaker.join("b", "Bob", "1V1", "Game-2"))
        val result = matchmaker.poll("Game-2")
        assertEquals(1, result.matches.size, "Game-2 hosts because Game-1 stopped polling")
        assertTrue(result.connect.isEmpty())
    }
}
