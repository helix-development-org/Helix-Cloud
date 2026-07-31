package org.helix.bridge.velocity

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeHttpClientTest {
    private var status = 401
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            if (status in 200..299) {
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.sendResponseHeaders(status, -1)
            }
            exchange.close()
        }
        start()
    }
    private val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val client = NodeHttpClient(
        BridgeSettings("Proxy-1", "http://127.0.0.1:${server.address.port}", "token"),
        warn = warnings::add,
    )

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `warns once per path while the same failure status persists`() {
        assertNull(client.getJsonLong("/api/v1/internal/poll?ackUpTo=1"))
        assertNull(client.getJsonLong("/api/v1/internal/poll?ackUpTo=2"))

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("401"))
        assertTrue(warnings.single().contains("/api/v1/internal/poll"))
    }

    @Test
    fun `warns again when the status changes or a failure returns after recovery`() {
        assertNull(client.getJson("/x")) // 401 -> warn
        status = 503
        assertNull(client.getJson("/x")) // status changed -> warn
        status = 200
        client.getJson("/x") // recovery resets the throttle
        status = 503
        assertNull(client.getJson("/x")) // failing anew -> warn

        assertEquals(3, warnings.size)
    }

    @Test
    fun `all verbs keep returning null or false on failure`() {
        status = 200
        assertTrue(client.postJson("/y", "{}"))
        status = 500
        assertFalse(client.postJson("/y", "{}"))
        assertNull(client.postJsonForBody("/y", "{}"))
        assertNull(client.getJson("/y"))

        assertEquals(1, warnings.size)
    }
}
