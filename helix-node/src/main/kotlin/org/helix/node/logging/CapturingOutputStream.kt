package org.helix.node.logging

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.text.Charsets.UTF_8

/**
 * Output stream that forwards everything to [origin] and mirrors complete
 * lines into a [LogBuffer].
 *
 * @property origin the real stream to keep writing to.
 * @property buffer the buffer receiving completed lines.
 */
class CapturingOutputStream(
    private val origin: OutputStream,
    private val buffer: LogBuffer,
) : OutputStream() {
    private val line = ByteArrayOutputStream(256)

    /**
     * Writes one byte through and captures line boundaries.
     *
     * @param b the byte to write.
     */
    @Synchronized
    override fun write(b: Int) {
        origin.write(b)
        capture(b)
    }

    /**
     * Writes a byte range through and captures line boundaries.
     *
     * @param b source bytes.
     * @param off start offset.
     * @param len number of bytes.
     */
    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        origin.write(b, off, len)
        for (index in off until off + len) {
            capture(b[index].toInt() and 0xFF)
        }
    }

    /** Flushes the underlying stream. */
    override fun flush() = origin.flush()

    private fun capture(byte: Int) {
        when (byte) {
            NEWLINE -> {
                buffer.add(line.toString(UTF_8))
                line.reset()
            }
            CARRIAGE_RETURN -> Unit
            else -> line.write(byte)
        }
    }

    private companion object {
        const val NEWLINE = 10
        const val CARRIAGE_RETURN = 13
    }
}
