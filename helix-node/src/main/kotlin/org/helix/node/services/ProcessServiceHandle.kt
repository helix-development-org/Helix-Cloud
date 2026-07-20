package org.helix.node.services

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ServiceHandle] over a local wrapper process.
 *
 * @property process the wrapper process.
 * @property logFile combined output of wrapper and server.
 */
class ProcessServiceHandle(
    private val process: Process,
    private val logFile: Path,
) : ServiceHandle {
    /** Whether the wrapper process is still running. */
    override val alive: Boolean
        get() = process.isAlive

    /**
     * Requests a graceful stop by terminating the wrapper, which forwards
     * the termination to the server.
     */
    override fun stop() {
        process.destroy()
    }

    /**
     * Kills the wrapper process immediately.
     */
    override fun kill() {
        process.destroyForcibly()
    }

    /**
     * Registers a callback invoked once when the process exits.
     *
     * @param callback receives the exit code.
     */
    override fun onExit(callback: (Int) -> Unit) {
        process.onExit().thenAccept { callback(it.exitValue()) }
    }

    /**
     * Reads the newest lines of `service.log`.
     *
     * @param tail maximum number of lines from the end.
     * @return the log lines, oldest first.
     */
    override fun logs(tail: Int): List<String> = try {
        Files.readAllLines(logFile).takeLast(tail)
    } catch (_: IOException) {
        emptyList()
    }
}
