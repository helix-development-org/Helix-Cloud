package org.helix.addons.npc.paper.villager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechQueueTest {
    private fun run(queue: SpeechQueue, ticks: Int): List<Pair<Int, SpeechEvent>> =
        (1..ticks).flatMap { t -> queue.tick().map { t to it } }

    @Test
    fun `a line starts with the hmm and babbles once per letter`() {
        val q = SpeechQueue(ticksPerLetter = 2)
        q.enqueue("Hi, du!")
        val events = run(q, 60)
        assertEquals(SpeechEvent.Line("Hi, du!"), events.first().second)
        assertEquals(4, events.count { it.second == SpeechEvent.Babble })
        assertFalse(q.speaking)
    }

    @Test
    fun `speaks while the line lasts`() {
        val q = SpeechQueue(ticksPerLetter = 1)
        q.enqueue("abc")
        q.tick()
        assertTrue(q.speaking)
        repeat(q.duration("abc")) { q.tick() }
        assertFalse(q.speaking)
    }

    @Test
    fun `pauses after two lines`() {
        val q = SpeechQueue(ticksPerLetter = 1)
        listOf("a", "b", "c").forEach { q.enqueue(it) }
        val starts = run(q, 300).filter { it.second is SpeechEvent.Line }.map { it.first }
        assertEquals(3, starts.size)
        val gap1 = starts[1] - starts[0]
        val gap2 = starts[2] - starts[1]
        assertEquals(SpeechQueue.PAUSE_TICKS - SpeechQueue.GAP_TICKS, gap2 - gap1)
    }

    @Test
    fun `drops lines beyond the queue limit`() {
        val q = SpeechQueue()
        repeat(SpeechQueue.MAX_QUEUE) { assertTrue(q.enqueue("x")) }
        assertFalse(q.enqueue("y"))
    }
}
