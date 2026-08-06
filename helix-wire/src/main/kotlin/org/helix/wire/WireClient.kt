package org.helix.wire

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory

/**
 * The Helix-Wire client a service uses to talk to the node over one
 * persistent, authenticated TCP connection.
 *
 * It keeps the connection alive with a background reader and a keepalive
 * pinger, reconnects with capped exponential backoff after a drop, and
 * exposes a synchronous [request] plus a [onPush] callback for
 * server-initiated events. Callers that need graceful degradation check
 * [isConnected] and fall back to HTTP while the wire is down.
 *
 * @property host the node host.
 * @property port the wire port.
 * @property serviceId this service's identity.
 * @property token this service's per-service token.
 * @property sslContext optional TLS context; `null` connects plaintext.
 * @property requestTimeoutMs how long [request] waits for a response.
 */
class WireClient(
    private val host: String,
    private val port: Int,
    private val serviceId: String,
    private val token: String,
    private val sslContext: SSLContext? = null,
    private val requestTimeoutMs: Long = 10_000,
) {
    private val logger = LoggerFactory.getLogger(WireClient::class.java)
    private val correlation = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<WireResponse>>()
    private val connection = AtomicReference<Link?>(null)
    private val running = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "helix-wire-client-$serviceId").apply { isDaemon = true } }
    private val keepalive = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "helix-wire-ping-$serviceId").apply { isDaemon = true }
    }

    @Volatile
    private var pushHandler: (category: String, payload: ByteArray) -> Unit = { _, _ -> }

    /**
     * Registers the callback for server pushes.
     *
     * @param handler receives the push category and CBOR payload.
     */
    fun onPush(handler: (category: String, payload: ByteArray) -> Unit) {
        pushHandler = handler
    }

    /**
     * Starts connecting; returns immediately and keeps reconnecting in the
     * background until [close].
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        io.execute { connectLoop() }
        keepalive.scheduleWithFixedDelay(::ping, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Whether the wire is currently connected and handshaken.
     *
     * @return `true` when a live link is available.
     */
    fun isConnected(): Boolean = connection.get() != null

    /**
     * Sends a request and blocks for the response.
     *
     * @param endpoint the endpoint name.
     * @param payload the CBOR request body.
     * @return the response.
     * @throws WireUnavailableException when no link is currently up.
     */
    fun request(endpoint: String, payload: ByteArray): WireResponse {
        val link = connection.get() ?: throw WireUnavailableException("wire not connected")
        val id = correlation.incrementAndGet()
        val future = CompletableFuture<WireResponse>()
        pending[id] = future
        try {
            if (!link.send(WireFrame(WireFrameType.REQUEST, id, endpoint, payload))) {
                throw WireUnavailableException("wire write failed")
            }
            return future.get(requestTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            throw WireUnavailableException("wire request failed: ${failure.message}")
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Closes the client and stops reconnecting.
     */
    fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        connection.getAndSet(null)?.close()
        failPending("client closed")
        keepalive.shutdownNow()
        io.shutdownNow()
    }

    private fun connectLoop() {
        var backoff = MIN_BACKOFF_MS
        while (running.get()) {
            val link = runCatching { connect() }.getOrNull()
            if (link == null) {
                if (!running.get()) return
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            backoff = MIN_BACKOFF_MS
            connection.set(link)
            logger.info("Helix-Wire connected to {}:{} as {}", host, port, serviceId)
            runCatching { readLoop(link) }
            connection.compareAndSet(link, null)
            link.close()
            failPending("wire disconnected")
            if (running.get()) {
                logger.warn("Helix-Wire lost connection to {}:{}; reconnecting", host, port)
            }
        }
    }

    private fun connect(): Link {
        val socket = if (sslContext != null) {
            sslContext.socketFactory.createSocket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        } else {
            Socket().also { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        }
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        WireProtocol.write(output, WireFrame(WireFrameType.HELLO, 0, "", WireCodec.encode(WireHello(WireProtocol.VERSION, serviceId, token))))
        val reply = WireProtocol.read(input) ?: throw WireProtocolException("no handshake reply")
        if (reply.type != WireFrameType.HELLO_OK) {
            val reason = runCatching { WireCodec.decode<WireHelloError>(reply.payload).reason }.getOrDefault("rejected")
            runCatching { socket.close() }
            throw WireProtocolException("handshake rejected: $reason")
        }
        return Link(socket, input, output)
    }

    private fun readLoop(link: Link) {
        while (running.get()) {
            val frame = WireProtocol.read(link.input) ?: break
            when (frame.type) {
                WireFrameType.RESPONSE -> pending.remove(frame.correlationId)?.complete(decodeResponse(frame.payload))
                WireFrameType.PUSH -> runCatching { pushHandler(frame.endpoint, frame.payload) }
                WireFrameType.PONG -> Unit
                WireFrameType.CLOSE -> break
                else -> Unit
            }
        }
    }

    private fun decodeResponse(payload: ByteArray): WireResponse =
        runCatching { WireCodec.decode<WireResponse>(payload) }
            .getOrElse { WireResponse.error("malformed response") }

    private fun ping() {
        connection.get()?.send(WireFrame(WireFrameType.PING, 0, "", ByteArray(0)))
    }

    private fun failPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(WireUnavailableException(reason)) }
        pending.clear()
    }

    private class Link(
        private val socket: Socket,
        val input: DataInputStream,
        private val output: DataOutputStream,
    ) {
        /** Writes a frame over this link, swallowing write failures. */
        fun send(frame: WireFrame): Boolean = runCatching { WireProtocol.write(output, frame) }.isSuccess

        /** Closes the underlying socket. */
        fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val PING_INTERVAL_MS = 10_000L
        const val MIN_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 10_000L
    }
}

/**
 * Raised when a wire request cannot be served because the connection is
 * down — the signal for the caller to fall back to HTTP.
 *
 * @param message the failure detail.
 */
class WireUnavailableException(message: String) : RuntimeException(message)
