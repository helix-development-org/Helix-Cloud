package org.helix.wire

import kotlinx.serialization.Serializable

/**
 * The handshake a client sends as the payload of its [WireFrameType.HELLO]
 * frame.
 *
 * @property version the protocol version the client speaks.
 * @property serviceId the service identity the client claims.
 * @property token the per-service token authenticating the claim, checked
 *   against the node's token registry exactly like the HTTP bearer token.
 */
@Serializable
data class WireHello(
    val version: Int,
    val serviceId: String,
    val token: String,
)

/**
 * The node's answer to a rejected handshake ([WireFrameType.HELLO_ERR]).
 *
 * @property reason human-readable rejection reason.
 */
@Serializable
data class WireHelloError(val reason: String)

/**
 * The reply payload of a [WireFrameType.RESPONSE] frame.
 *
 * A wire request never throws across the connection: a handler failure
 * comes back as `ok = false` with a [message], mirroring how the HTTP side
 * turns errors into status bodies, so the caller can decide to fall back.
 *
 * @property ok whether the endpoint handled the request successfully.
 * @property message failure detail when [ok] is `false`.
 * @property body the endpoint's CBOR-encoded response payload when [ok] is
 *   `true`; empty for responses without a body.
 */
@Serializable
data class WireResponse(
    val ok: Boolean,
    val message: String = "",
    val body: ByteArray = ByteArray(0),
) {
    /**
     * Value equality including the body bytes.
     *
     * @param other the object to compare.
     * @return `true` when ok, message and body all match.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireResponse) return false
        return ok == other.ok && message == other.message && body.contentEquals(other.body)
    }

    /**
     * Hash consistent with [equals].
     *
     * @return the combined hash.
     */
    override fun hashCode(): Int {
        var result = ok.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }

    companion object {
        /**
         * A successful response carrying a body.
         *
         * @param body the CBOR-encoded response payload.
         * @return the response.
         */
        fun ok(body: ByteArray = ByteArray(0)): WireResponse = WireResponse(true, "", body)

        /**
         * A failed response carrying a reason.
         *
         * @param message the failure detail.
         * @return the response.
         */
        fun error(message: String): WireResponse = WireResponse(false, message, ByteArray(0))
    }
}
