package org.helix.node.services

import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessServiceHandleTest {
    @Test
    fun `sendCommand writes a line to the process stdin`() {
        val directory = createTempDirectory("svc")
        val logFile = directory.resolve("service.log")
        // `cat` echoes its stdin to stdout, which we redirect to the log file.
        val process = ProcessBuilder("cat")
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
        val handle = ProcessServiceHandle(process, logFile)

        assertTrue(handle.sendCommand("hello world"))
        // Close stdin so `cat` flushes its output and exits deterministically.
        process.outputStream.close()
        process.waitFor(5, TimeUnit.SECONDS)

        assertTrue(logFile.readText().contains("hello world"))
        assertFalse(handle.sendCommand("after exit")) // process has exited
    }
}
