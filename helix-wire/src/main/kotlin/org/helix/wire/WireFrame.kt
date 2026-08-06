package org.helix.wire

/**
 * Frame type of a single Helix-Wire message, encoded as the first byte of
 * every frame on the wire.
 *
 * The protocol multiplexes request/response and server push over one
 * persistent TCP connection: a [REQUEST] carries a correlation id the peer
 * echoes in its [RESPONSE], while [PUSH] frames are server-initiated and
 * uncorrelated.
 */
enum class WireFrameType(
    /** The byte written as the frame's type tag. */
    val tag: Byte,
) {
    /** Client → server: opening handshake with token and service id. */
    HELLO(1),

    /** Server → client: handshake accepted. */
    HELLO_OK(2),

    /** Server → client: handshake rejected, connection closes after. */
    HELLO_ERR(3),

    /** Either direction: an endpoint call awaiting a [RESPONSE]. */
    REQUEST(4),

    /** Either direction: the reply to a [REQUEST] with the same id. */
    RESPONSE(5),

    /** Server → client: an unsolicited event (replaces the HTTP long-poll). */
    PUSH(6),

    /** Keepalive probe. */
    PING(7),

    /** Keepalive answer. */
    PONG(8),

    /** Graceful close notice. */
    CLOSE(9),
    ;

    companion object {
        private val byTag = entries.associateBy { it.tag }

        /**
         * Resolves a frame type from its wire tag byte.
         *
         * @param tag the type byte read from the wire.
         * @return the matching type, or `null` for an unknown tag.
         */
        fun fromTag(tag: Byte): WireFrameType? = byTag[tag]
    }
}

/**
 * One decoded Helix-Wire frame.
 *
 * The wire layout is fixed-header + payload, big-endian:
 * `[4 bytes total length][1 byte type][8 bytes correlationId][2 bytes endpoint length][endpoint utf-8][payload]`,
 * where the leading length counts every byte after itself.
 *
 * @property type the frame type.
 * @property correlationId request/response pairing id; `0` for frames that
 *   need no correlation (PUSH, PING, PONG, CLOSE, HELLO*).
 * @property endpoint the logical endpoint name for [WireFrameType.REQUEST]
 *   and the category for [WireFrameType.PUSH]; empty otherwise.
 * @property payload the CBOR-encoded body, may be empty.
 */
data class WireFrame(
    val type: WireFrameType,
    val correlationId: Long,
    val endpoint: String,
    val payload: ByteArray,
) {
    /**
     * Value equality including the payload bytes.
     *
     * @param other the object to compare.
     * @return `true` when type, id, endpoint and payload all match.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireFrame) return false
        return type == other.type &&
            correlationId == other.correlationId &&
            endpoint == other.endpoint &&
            payload.contentEquals(other.payload)
    }

    /**
     * Hash consistent with [equals].
     *
     * @return the combined hash.
     */
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + correlationId.hashCode()
        result = 31 * result + endpoint.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    /**
     * Debug string that omits the raw payload bytes.
     *
     * @return a concise description.
     */
    override fun toString(): String =
        "WireFrame(type=$type, id=$correlationId, endpoint='$endpoint', payload=${payload.size}B)"
}
