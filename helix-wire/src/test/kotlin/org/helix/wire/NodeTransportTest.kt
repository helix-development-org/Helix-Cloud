package org.helix.wire

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeTransportTest {
    private val port = ServerSocket(0).use { it.localPort }
    private var server: WireServer? = null
    private var transport: NodeTransport? = null

    @AfterTest
    fun tearDown() {
        transport?.close()
        server?.stop()
    }

    private val fallbackCalls = AtomicInteger()
    private val httpFallback: (String, ByteArray) -> WireResponse = { endpoint, _ ->
        fallbackCalls.incrementAndGet()
        WireResponse.ok("http:$endpoint".toByteArray())
    }

    private fun awaitWire(t: NodeTransport, up: Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (t.isWireActive() != up && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(up, t.isWireActive())
    }

    @Test
    fun `a plain http url yields the http-only transport`() {
        val t = NodeTransport.fromControlUrl("http://127.0.0.1:8080", "Lobby-1", "tok", httpFallback)
        transport = t
        t.start()

        val response = t.request("heartbeat", ByteArray(0))

        assertFalse(t.isWireActive())
        assertEquals("http:heartbeat", String(response.body))
        assertEquals(1, fallbackCalls.get())
    }

    @Test
    fun `a helix url uses the wire when connected`() {
        val srv = WireServer(port, "127.0.0.1", { _, t -> t == "tok" }).also { it.start(); server = it }
        srv.handle("action") { _, payload -> WireResponse.ok("wire".toByteArray() + payload) }
        val t = NodeTransport.fromControlUrl("helix://127.0.0.1:$port", "Lobby-1", "tok", httpFallback)
        transport = t
        t.start()
        awaitWire(t, true)

        val response = t.request("action", byteArrayOf(7))

        assertTrue(response.ok)
        assertEquals("wire", String(response.body.copyOfRange(0, 4)))
        assertEquals(0, fallbackCalls.get())
    }

    @Test
    fun `a helix url falls back to http while the wire is down`() {
        val srv = WireServer(port, "127.0.0.1", { _, t -> t == "tok" }).also { it.start(); server = it }
        val t = NodeTransport.fromControlUrl("helix://127.0.0.1:$port", "Lobby-1", "tok", httpFallback)
        transport = t
        t.start()
        awaitWire(t, true)

        srv.stop()
        awaitWire(t, false)
        val response = t.request("heartbeat", ByteArray(0))

        assertEquals("http:heartbeat", String(response.body))
        assertTrue(fallbackCalls.get() >= 1)
    }
}
