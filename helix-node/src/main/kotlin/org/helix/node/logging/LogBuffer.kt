package org.helix.node.logging

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.text.Charsets.UTF_8

/**
 * In-memory ring buffer of the node's most recent log lines.
 *
 * The buffer is filled by teeing `System.out`/`System.err` so it captures
 * everything the node and its libraries print, and exposed to the
 * dashboard through the control API.
 *
 * @property capacity maximum number of retained lines.
 */
class LogBuffer(private val capacity: Int = 2000) {
    private val lines = ArrayDeque<String>()

    /**
     * Appends a line, dropping the oldest when the capacity is reached.
     *
     * @param line the log line without trailing newline.
     */
    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > capacity) {
            lines.removeFirst()
        }
    }

    /**
     * Returns the newest lines.
     *
     * @param limit maximum number of lines from the end.
     * @return the lines, oldest first.
     */
    @Synchronized
    fun tail(limit: Int): List<String> = lines.toList().takeLast(limit)
}

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

/**
 * Installs the log capture, teeing standard output and error into a shared
 * [LogBuffer].
 */
object LogCapture {
    /**
     * Redirects `System.out` and `System.err` through capturing streams.
     *
     * @param capacity maximum number of retained lines.
     * @return the buffer receiving all output.
     */
    fun install(capacity: Int = 2000): LogBuffer {
        val buffer = LogBuffer(capacity)
        System.setOut(PrintStream(CapturingOutputStream(System.out, buffer), true, UTF_8))
        System.setErr(PrintStream(CapturingOutputStream(System.err, buffer), true, UTF_8))
        return buffer
    }
}
