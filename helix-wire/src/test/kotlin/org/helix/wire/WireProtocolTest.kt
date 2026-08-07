package org.helix.wire

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireProtocolTest {
    private fun roundtrip(frame: WireFrame): WireFrame {
        val buffer = ByteArrayOutputStream()
        WireProtocol.write(DataOutputStream(buffer), frame)
        return WireProtocol.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))!!
    }

    @Test
    fun `frames survive a write-read roundtrip`() {
        val frame = WireFrame(WireFrameType.REQUEST, 42, "heartbeat", byteArrayOf(1, 2, 3, 4))
        assertEquals(frame, roundtrip(frame))
    }

    @Test
    fun `empty endpoint and payload roundtrip`() {
        val frame = WireFrame(WireFrameType.PING, 0, "", ByteArray(0))
        assertEquals(frame, roundtrip(frame))
    }

    @Test
    fun `unicode endpoints survive`() {
        val frame = WireFrame(WireFrameType.PUSH, 0, "routing.änderung", byteArrayOf(9))
        assertEquals(frame, roundtrip(frame))
    }

    @Test
    fun `multiple frames read back in order from one stream`() {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        val frames = listOf(
            WireFrame(WireFrameType.HELLO, 0, "", byteArrayOf(1)),
            WireFrame(WireFrameType.REQUEST, 1, "a", byteArrayOf(2)),
            WireFrame(WireFrameType.RESPONSE, 1, "", byteArrayOf(3)),
        )
        frames.forEach { WireProtocol.write(out, it) }

        val input = DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals(frames, listOf(WireProtocol.read(input), WireProtocol.read(input), WireProtocol.read(input)))
        assertNull(WireProtocol.read(input))
    }

    @Test
    fun `a truncated stream reads as clean end of input`() {
        assertNull(WireProtocol.read(DataInputStream(ByteArrayInputStream(ByteArray(0)))))
    }

    @Test
    fun `an absurd length is rejected`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(WireProtocol.MAX_FRAME_BYTES + 1)
        assertFailsWith<WireProtocolException> {
            WireProtocol.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
        }
    }

    @Test
    fun `an unknown frame type is rejected`() {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeInt(1 + 8 + 2)
        out.writeByte(127)
        out.writeLong(0)
        out.writeShort(0)
        assertFailsWith<WireProtocolException> {
            WireProtocol.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
        }
    }

    @Serializable
    data class Sample(val name: String, val count: Int, val flags: List<Boolean>)

    @Test
    fun `cbor codec roundtrips existing style dtos`() {
        val value = Sample("Lobby-1", 7, listOf(true, false, true))
        val bytes = WireCodec.encode(value)
        assertEquals(value, WireCodec.decode<Sample>(bytes))
    }

    @Test
    fun `wire response helpers carry ok and error state`() {
        val ok = WireResponse.ok(byteArrayOf(5, 6))
        assertTrue(ok.ok)
        assertEquals(WireResponse.ok(byteArrayOf(5, 6)), ok)

        val error = WireResponse.error("nope")
        assertTrue(!error.ok)
        assertEquals("nope", error.message)
    }

    @Test
    fun `handshake dto roundtrips through cbor`() {
        val hello = WireHello(WireProtocol.VERSION, "Lobby-1", "tok")
        assertEquals(hello, WireCodec.decode<WireHello>(WireCodec.encode(hello)))
    }
}
