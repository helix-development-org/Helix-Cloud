package org.helix.node.services

import java.io.IOException
import java.io.Writer
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
    /** Live pipe to the wrapper's (and thus the server's) stdin. */
    private val stdin: Writer by lazy { process.outputStream.bufferedWriter() }

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

    /**
     * Writes a command line to the wrapper's stdin, which the server inherits.
     *
     * @param line the command, without a trailing newline.
     * @return `true` if delivered; `false` when the process has exited or the
     *  pipe is closed.
     */
    @Synchronized
    override fun sendCommand(line: String): Boolean {
        if (!process.isAlive) {
            return false
        }
        return try {
            stdin.write(line)
            stdin.write("\n")
            stdin.flush()
            true
        } catch (_: IOException) {
            false
        }
    }
}
