package org.helix.wire

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireServerClientTest {
    private val port = freePort()
    private var server: WireServer? = null
    private var client: WireClient? = null

    @AfterTest
    fun tearDown() {
        client?.close()
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startServer(auth: (String, String) -> Boolean = { _, t -> t == "good" }): WireServer =
        WireServer(port, "127.0.0.1", auth).also { it.start(); server = it }

    private fun startClient(token: String = "good"): WireClient =
        WireClient("127.0.0.1", port, "Lobby-1", token).also { it.start(); client = it }

    private fun awaitConnected(c: WireClient) {
        val deadline = System.currentTimeMillis() + 3000
        while (!c.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(c.isConnected(), "client did not connect in time")
    }

    @Test
    fun `request reaches the handler and the response comes back`() {
        val srv = startServer()
        srv.handle("echo") { serviceId, payload ->
            WireResponse.ok((serviceId + ":").toByteArray() + payload)
        }
        val c = startClient()
        awaitConnected(c)

        val response = c.request("echo", byteArrayOf(1, 2, 3))

        assertTrue(response.ok)
        assertEquals("Lobby-1:", String(response.body.copyOfRange(0, 8)))
    }

    @Test
    fun `unknown endpoints and throwing handlers return errors not crashes`() {
        val srv = startServer()
        srv.handle("boom") { _, _ -> error("kaputt") }
        val c = startClient()
        awaitConnected(c)

        assertFalse(c.request("nope", ByteArray(0)).ok)
        val boom = c.request("boom", ByteArray(0))
        assertFalse(boom.ok)
        assertTrue(boom.message.contains("kaputt"))
    }

    @Test
    fun `a bad token is rejected and the client never connects`() {
        startServer()
        val c = startClient(token = "wrong")

        Thread.sleep(600)
        assertFalse(c.isConnected())
        assertFailsWith<WireUnavailableException> { c.request("echo", ByteArray(0)) }
    }

    @Test
    fun `server push reaches the client`() {
        val srv = startServer()
        val received = AtomicReference<Pair<String, ByteArray>>()
        val latch = CountDownLatch(1)
        val c = startClient()
        c.onPush { category, payload -> received.set(category to payload); latch.countDown() }
        awaitConnected(c)
        // the server sees the connection a hair after the client; wait for it
        val deadline = System.currentTimeMillis() + 2000
        while (!srv.isConnected("Lobby-1") && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue(srv.push("Lobby-1", "routing", byteArrayOf(9, 9)))
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("routing", received.get().first)
        assertEquals(listOf<Byte>(9, 9), received.get().second.toList())
    }

    @Test
    fun `the client reconnects after the server drops it`() {
        val first = startServer()
        val c = startClient()
        awaitConnected(c)

        first.stop()
        Thread.sleep(300)
        assertFalse(c.isConnected())

        // a fresh server on the same port; the client's backoff loop finds it
        val second = WireServer(port, "127.0.0.1", { _, t -> t == "good" }).also { it.start(); server = it }
        second.handle("echo") { _, payload -> WireResponse.ok(payload) }
        awaitConnected(c)

        assertTrue(c.request("echo", byteArrayOf(7)).ok)
    }

    @Test
    fun `requests fail fast while the wire is down`() {
        val srv = startServer()
        val c = startClient()
        awaitConnected(c)
        srv.stop()
        Thread.sleep(300)

        assertFailsWith<WireUnavailableException> { c.request("echo", ByteArray(0)) }
    }
}
