package org.helix.wire

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Reads and writes [WireFrame]s in the Helix-Wire framing.
 *
 * Framing is length-prefixed so a reader always knows how many bytes a
 * frame occupies before decoding it, which keeps the protocol robust
 * against partial reads on the raw socket. A per-frame size ceiling guards
 * against a peer (or corruption) announcing an absurd length.
 */
object WireProtocol {
    /** Current protocol version, exchanged in the handshake. */
    const val VERSION: Int = 1

    /** Largest frame accepted, in bytes (16 MiB) — a safety ceiling. */
    const val MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

    /**
     * Writes one frame to the stream and flushes it.
     *
     * @param out the destination stream.
     * @param frame the frame to serialize.
     */
    fun write(out: DataOutputStream, frame: WireFrame) {
        val endpointBytes = frame.endpoint.toByteArray(Charsets.UTF_8)
        // total = type(1) + correlationId(8) + endpointLen(2) + endpoint + payload
        val total = 1 + 8 + 2 + endpointBytes.size + frame.payload.size
        require(total <= MAX_FRAME_BYTES) { "frame too large: $total bytes" }
        synchronized(out) {
            out.writeInt(total)
            out.writeByte(frame.type.tag.toInt())
            out.writeLong(frame.correlationId)
            out.writeShort(endpointBytes.size)
            out.write(endpointBytes)
            out.write(frame.payload)
            out.flush()
        }
    }

    /**
     * Reads one frame from the stream, blocking until it is complete.
     *
     * @param input the source stream.
     * @return the decoded frame, or `null` at a clean end of stream.
     * @throws WireProtocolException on a malformed or oversized frame.
     */
    fun read(input: DataInputStream): WireFrame? {
        val total = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (total < 1 + 8 + 2 || total > MAX_FRAME_BYTES) {
            throw WireProtocolException("invalid frame length: $total")
        }
        val tag = input.readByte()
        val type = WireFrameType.fromTag(tag)
            ?: throw WireProtocolException("unknown frame type: $tag")
        val correlationId = input.readLong()
        val endpointLength = input.readUnsignedShort()
        val fixed = 1 + 8 + 2
        if (endpointLength > total - fixed) {
            throw WireProtocolException("endpoint length $endpointLength exceeds frame")
        }
        val endpointBytes = ByteArray(endpointLength)
        input.readFully(endpointBytes)
        val payload = ByteArray(total - fixed - endpointLength)
        input.readFully(payload)
        return WireFrame(type, correlationId, endpointBytes.toString(Charsets.UTF_8), payload)
    }
}

/**
 * Raised when a frame violates the Helix-Wire framing rules.
 *
 * @param message the failure detail.
 */
class WireProtocolException(message: String) : RuntimeException(message)
