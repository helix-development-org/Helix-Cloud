package org.helix.node.services

import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessServiceHandleTest {
    @Test
    fun `sendCommand appends console lines while the process is alive`() {
        val workspace = createTempDirectory("svc")
        val logFile = workspace.resolve("service.log")
        val process = ProcessBuilder("sleep", "30").start()
        val handle = ProcessServiceHandle(process, logFile)

        assertTrue(handle.sendCommand("say hello"))
        assertTrue(handle.sendCommand("list"))
        assertEquals("say hello\nlist\n", workspace.resolve("console.in").readText())

        process.destroyForcibly()
        process.waitFor()
        assertFalse(handle.sendCommand("after exit")) // no longer running
    }
}
