package org.helix.wire

import javax.net.ssl.SSLContext

/**
 * A service's transport to the node, hiding whether calls travel over
 * Helix-Wire or plain HTTP.
 *
 * A bridge or addon component calls [request] with an endpoint name and a
 * CBOR payload and gets a [WireResponse] back, regardless of transport.
 * When the wire is enabled and connected the call goes over it; otherwise
 * (wire disabled, or momentarily disconnected) it transparently falls back
 * to the HTTP path the caller supplies — so a wire outage never causes a
 * functional outage.
 */
interface NodeTransport {
    /**
     * Performs one request/response call.
     *
     * @param endpoint the endpoint name (mirrors the HTTP `internal/` path).
     * @param payload the CBOR request body.
     * @return the response; a failed [WireResponse] carries the reason.
     */
    fun request(endpoint: String, payload: ByteArray): WireResponse

    /**
     * Registers the callback for server pushes (only ever fires over the
     * wire; the HTTP transport never pushes).
     *
     * @param handler receives the push category and CBOR payload.
     */
    fun onPush(handler: (category: String, payload: ByteArray) -> Unit)

    /**
     * Whether the wire is currently the active path (`false` for the pure
     * HTTP transport or while the wire is reconnecting).
     *
     * @return `true` when calls are currently served over the wire.
     */
    fun isWireActive(): Boolean

    /**
     * Starts the transport (connecting the wire when applicable).
     */
    fun start()

    /**
     * Closes the transport.
     */
    fun close()

    companion object {
        /**
         * Builds the right transport from a service's control URL.
         *
         * A `helix://` or `helixs://` url yields a wire transport (TLS for
         * `helixs`) that falls back to [httpFallback] while disconnected;
         * any other scheme yields a pure HTTP transport that always uses
         * [httpFallback]. This is exactly the toggle: the node hands out a
         * `helix://` url only when `[wire] enabled` is set.
         *
         * @param controlUrl the primary control url from `HELIX_CONTROL_URL`.
         * @param serviceId this service's id.
         * @param token this service's per-service token.
         * @param httpFallback performs a call over HTTP; the wire transport
         *   uses it while disconnected and the HTTP transport always.
         * @param sslContext optional TLS context for `helixs://`.
         * @return the transport (not yet started).
         */
        fun fromControlUrl(
            controlUrl: String,
            serviceId: String,
            token: String,
            httpFallback: (endpoint: String, payload: ByteArray) -> WireResponse,
            sslContext: SSLContext? = null,
        ): NodeTransport {
            val scheme = controlUrl.substringBefore("://", "").lowercase()
            if (scheme != "helix" && scheme != "helixs") {
                return HttpNodeTransport(httpFallback)
            }
            val authority = controlUrl.substringAfter("://").substringBefore('/')
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', "8090").toIntOrNull() ?: 8090
            val tls = scheme == "helixs"
            return WireNodeTransport(
                client = WireClient(host, port, serviceId, token, if (tls) sslContext else null),
                httpFallback = httpFallback,
            )
        }
    }
}

/**
 * The pure-HTTP transport used when the wire is disabled.
 *
 * @property httpFallback performs every call over HTTP.
 */
class HttpNodeTransport(
    private val httpFallback: (endpoint: String, payload: ByteArray) -> WireResponse,
) : NodeTransport {
    override fun request(endpoint: String, payload: ByteArray): WireResponse = httpFallback(endpoint, payload)

    override fun onPush(handler: (category: String, payload: ByteArray) -> Unit) {
    }

    override fun isWireActive(): Boolean = false

    override fun start() {
    }

    override fun close() {
    }
}

/**
 * The wire transport with HTTP fallback.
 *
 * @property client the underlying wire client.
 * @property httpFallback used while the wire is not connected.
 */
class WireNodeTransport(
    private val client: WireClient,
    private val httpFallback: (endpoint: String, payload: ByteArray) -> WireResponse,
) : NodeTransport {
    override fun request(endpoint: String, payload: ByteArray): WireResponse =
        if (client.isConnected()) {
            try {
                client.request(endpoint, payload)
            } catch (_: WireUnavailableException) {
                httpFallback(endpoint, payload)
            }
        } else {
            httpFallback(endpoint, payload)
        }

    override fun onPush(handler: (category: String, payload: ByteArray) -> Unit) = client.onPush(handler)

    override fun isWireActive(): Boolean = client.isConnected()

    override fun start() = client.start()

    override fun close() = client.close()
}
