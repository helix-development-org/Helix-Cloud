package org.helix.node.logging

import java.io.PrintStream
import kotlin.text.Charsets.UTF_8

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
