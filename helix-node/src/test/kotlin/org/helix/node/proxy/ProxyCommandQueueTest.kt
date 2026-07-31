package org.helix.node.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.proxy.ProxyCommand

class ProxyCommandQueueTest {

    @Test
    fun `pending and acknowledge deliver and then remove commands`() {
        val queue = ProxyCommandQueue()
        queue.enqueue(listOf("Proxy-1"), ProxyCommand.kick("steve", "banned"))

        val snapshot = queue.pending("Proxy-1")
        assertEquals(listOf("steve"), snapshot.map { it.command.player })

        queue.acknowledge("Proxy-1", queue.tokenFor(snapshot, previous = 0L))
        assertTrue(queue.pending("Proxy-1").isEmpty())
    }

    @Test
    fun `ack token derived from the snapshot never covers a command enqueued after it`() {
        val queue = ProxyCommandQueue()
        queue.enqueue(listOf("Proxy-1"), ProxyCommand.kick("steve", "banned"))

        // the long-poll reads its response snapshot ...
        val snapshot = queue.pending("Proxy-1")
        assertEquals(1, snapshot.size)

        // ... and a second command races in before the ack token is computed
        queue.enqueue(listOf("Proxy-1"), ProxyCommand.kick("alex", "banned"))

        val token = queue.tokenFor(snapshot, previous = 0L)

        // the proxy's next poll acks that token — the raced-in command,
        // which was never part of a response, must survive the ack
        queue.acknowledge("Proxy-1", token)
        assertEquals(listOf("alex"), queue.pending("Proxy-1").map { it.command.player })
    }

    @Test
    fun `an empty snapshot keeps the previous ack token`() {
        val queue = ProxyCommandQueue()

        assertEquals(7L, queue.tokenFor(queue.pending("Proxy-1"), previous = 7L))
    }

    @Test
    fun `drop discards the queue of a proxy that is gone`() {
        val queue = ProxyCommandQueue()
        queue.enqueue(listOf("Proxy-1", "Proxy-2"), ProxyCommand.kick("steve", "banned"))

        queue.drop("Proxy-1")

        assertTrue(queue.pending("Proxy-1").isEmpty())
        assertEquals(1, queue.pending("Proxy-2").size)
    }
}
