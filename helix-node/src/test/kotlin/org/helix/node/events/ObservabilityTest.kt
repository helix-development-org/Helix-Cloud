package org.helix.node.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.node.logging.LogBuffer

class ObservabilityTest {
    @Test
    fun `event log keeps newest first and respects capacity`() {
        var now = 0L
        val log = EventLog(capacity = 3, clock = { now })
        repeat(5) { now = it.toLong(); log.record("service", "event $it") }

        val recent = log.recent(10)

        assertEquals(listOf("event 4", "event 3", "event 2"), recent.map { it.message })
        assertEquals(4L, recent.first().epochMs)
        assertEquals("service", recent.first().category)
    }

    @Test
    fun `event level is retained`() {
        val log = EventLog(clock = { 1L })
        log.record("proxy", "boom", "error")

        assertEquals("error", log.recent(1).single().level)
    }

    @Test
    fun `log buffer tails and drops oldest lines`() {
        val buffer = LogBuffer(capacity = 2)
        buffer.add("a"); buffer.add("b"); buffer.add("c")

        assertEquals(listOf("b", "c"), buffer.tail(10))
        assertEquals(listOf("c"), buffer.tail(1))
    }

    @Test
    fun `capturing stream mirrors complete lines and forwards bytes`() {
        val buffer = LogBuffer()
        val forwarded = java.io.ByteArrayOutputStream()
        val stream = org.helix.node.logging.CapturingOutputStream(forwarded, buffer)

        stream.write("hello\nwör".toByteArray())
        stream.write('l'.code)
        stream.write("d\n".toByteArray())

        assertEquals(listOf("hello", "wörld"), buffer.tail(10))
        assertTrue(forwarded.toString(Charsets.UTF_8).contains("hello\nwörld\n"))
    }
}
