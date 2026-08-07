package org.helix.wire

import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

/**
 * A request handler for one wire endpoint.
 *
 * The handler receives the calling service id and the raw CBOR request
 * payload and returns the response to send back. It must not throw: a
 * failure should be returned as [WireResponse.error]; an exception is
 * caught and turned into an error response so one broken handler cannot
 * take down the connection.
 */
fun interface WireRequestHandler {
    /**
     * Handles one endpoint request.
     *
     * @param serviceId the authenticated calling service.
     * @param payload the CBOR request body.
     * @return the response to return to the caller.
     */
    fun handle(serviceId: String, payload: ByteArray): WireResponse
}

/**
 * The Helix-Wire server: accepts persistent TCP connections from services,
 * authenticates each with a per-service token handshake, dispatches
 * [WireFrameType.REQUEST] frames to registered [WireRequestHandler]s and
 * can push server-initiated events to any connected service.
 *
 * It uses plain blocking sockets with one thread per connection — a Helix
 * network has dozens of services, not thousands, so a thread pool is
 * simpler and more robust than an async reactor and carries no Netty
 * dependency to clash with a platform's own.
 *
 * @property port the TCP port to listen on.
 * @property host the bind interface.
 * @property authenticate resolves a presented (serviceId, token) pair to
 *   `true` when the token is valid for that service; the same check the
 *   HTTP bearer auth performs.
 * @property sslContext optional TLS context; `null` serves plaintext.
 */
class WireServer(
    private val port: Int,
    private val host: String,
    private val authenticate: (serviceId: String, token: String) -> Boolean,
    private val sslContext: SSLContext? = null,
) {
    private val logger = LoggerFactory.getLogger(WireServer::class.java)
    private val handlers = ConcurrentHashMap<String, WireRequestHandler>()
    private val connections = ConcurrentHashMap<String, WireConnection>()
    private val running = AtomicBoolean(false)
    private val accepts = Executors.newSingleThreadExecutor { r -> Thread(r, "helix-wire-accept").apply { isDaemon = true } }
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "helix-wire-conn").apply { isDaemon = true } }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var onDisconnect: (serviceId: String) -> Unit = {}

    /**
     * Registers a handler for an endpoint, replacing any previous one.
     *
     * @param endpoint the endpoint name.
     * @param handler the handler to invoke for that endpoint.
     */
    fun handle(endpoint: String, handler: WireRequestHandler) {
        handlers[endpoint] = handler
    }

    /**
     * Registers a callback invoked when a service's connection drops, so
     * the node can mirror the HTTP path's disconnect handling.
     *
     * @param listener receives the service id that disconnected.
     */
    fun onDisconnect(listener: (serviceId: String) -> Unit) {
        onDisconnect = listener
    }

    /**
     * Starts listening; returns immediately, accepting on a background
     * thread.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        val socket = if (sslContext != null) {
            sslContext.serverSocketFactory.createServerSocket(port, 50, java.net.InetAddress.getByName(host))
        } else {
            ServerSocket(port, 50, java.net.InetAddress.getByName(host))
        }
        serverSocket = socket
        logger.info("Helix-Wire listening on {}:{} ({})", host, port, if (sslContext != null) "TLS" else "plain")
        accepts.execute { acceptLoop(socket) }
    }

    /**
     * Whether a given service currently holds an open wire connection.
     *
     * @param serviceId the service id.
     * @return `true` when connected.
     */
    fun isConnected(serviceId: String): Boolean = connections.containsKey(serviceId)

    /**
     * Pushes a server-initiated event to one connected service.
     *
     * @param serviceId the target service.
     * @param category the push category (mirrors the HTTP long-poll topic).
     * @param payload the CBOR event body.
     * @return `true` when the service was connected and the frame was
     *   queued.
     */
    fun push(serviceId: String, category: String, payload: ByteArray): Boolean {
        val connection = connections[serviceId] ?: return false
        return connection.send(WireFrame(WireFrameType.PUSH, 0, category, payload))
    }

    /**
     * Stops the server and closes all connections.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        runCatching { serverSocket?.close() }
        connections.values.forEach { it.close() }
        connections.clear()
        accepts.shutdownNow()
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (failure: Exception) {
                if (running.get()) logger.warn("Wire accept failed", failure)
                continue
            }
            workers.execute { serve(client) }
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        val serviceId = handshake(input, output) ?: run { runCatching { socket.close() }; return }
        val connection = WireConnection(serviceId, socket, output)
        connections[serviceId]?.close()
        connections[serviceId] = connection
        logger.info("Wire service {} connected", serviceId)
        try {
            readLoop(connection, input)
        } finally {
            connections.remove(serviceId, connection)
            connection.close()
            logger.info("Wire service {} disconnected", serviceId)
            runCatching { onDisconnect(serviceId) }
        }
    }

    private fun handshake(input: DataInputStream, output: DataOutputStream): String? {
        val frame = runCatching { WireProtocol.read(input) }.getOrNull() ?: return null
        if (frame.type != WireFrameType.HELLO) {
            return null
        }
        val hello = runCatching { WireCodec.decode<WireHello>(frame.payload) }.getOrNull()
            ?: return reject(output, "malformed handshake")
        if (hello.version != WireProtocol.VERSION) {
            return reject(output, "protocol version ${hello.version} not supported")
        }
        if (!authenticate(hello.serviceId, hello.token)) {
            return reject(output, "authentication failed")
        }
        WireProtocol.write(output, WireFrame(WireFrameType.HELLO_OK, 0, "", ByteArray(0)))
        return hello.serviceId
    }

    private fun reject(output: DataOutputStream, reason: String): String? {
        runCatching {
            WireProtocol.write(
                output,
                WireFrame(WireFrameType.HELLO_ERR, 0, "", WireCodec.encode(WireHelloError(reason))),
            )
        }
        return null
    }

    private fun readLoop(connection: WireConnection, input: DataInputStream) {
        while (running.get()) {
            val frame = try {
                WireProtocol.read(input) ?: break
            } catch (_: Exception) {
                break
            }
            when (frame.type) {
                WireFrameType.REQUEST -> workers.execute { dispatch(connection, frame) }
                WireFrameType.PING -> connection.send(WireFrame(WireFrameType.PONG, frame.correlationId, "", ByteArray(0)))
                WireFrameType.CLOSE -> return
                else -> Unit
            }
        }
    }

    private fun dispatch(connection: WireConnection, frame: WireFrame) {
        val handler = handlers[frame.endpoint]
        val response = if (handler == null) {
            WireResponse.error("unknown endpoint: ${frame.endpoint}")
        } else {
            try {
                handler.handle(connection.serviceId, frame.payload)
            } catch (failure: Exception) {
                logger.warn("Wire endpoint {} failed", frame.endpoint, failure)
                WireResponse.error("${frame.endpoint} failed: ${failure.message}")
            }
        }
        connection.send(WireFrame(WireFrameType.RESPONSE, frame.correlationId, "", WireCodec.encode(response)))
    }

    private class WireConnection(
        val serviceId: String,
        private val socket: Socket,
        private val output: DataOutputStream,
    ) {
        /** Writes a frame to this connection, swallowing write failures. */
        fun send(frame: WireFrame): Boolean = runCatching { WireProtocol.write(output, frame) }.isSuccess

        /** Closes the underlying socket. */
        fun close() {
            runCatching { socket.close() }
        }
    }
}
