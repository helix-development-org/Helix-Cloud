package org.helix.node.services

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ServiceHandle] over a wrapper process that outlived a node restart.
 *
 * A fresh node JVM cannot reconstruct the original [java.lang.Process], so
 * the surviving wrapper is controlled through its [ProcessHandle] instead.
 * The exit code of an adopted process is not observable — exits report code
 * `0`, so an adopted service never shows up as `FAILED`.
 *
 * @property processHandle OS handle of the surviving wrapper process.
 * @property logFile combined output of wrapper and server.
 */
class AdoptedProcessHandle(
    private val processHandle: ProcessHandle,
    private val logFile: Path,
) : ServiceHandle {
    /** Whether the wrapper process is still running. */
    override val alive: Boolean
        get() = processHandle.isAlive

    /** OS process id of the wrapper. */
    override val pid: Long
        get() = processHandle.pid()

    /** OS start instant of the wrapper, when the platform reports it. */
    override val startInstantEpochMs: Long?
        get() = runCatching { processHandle.info().startInstant().orElse(null)?.toEpochMilli() }.getOrNull()

    /**
     * Requests a graceful stop by terminating the wrapper, which forwards
     * the termination to the server.
     */
    override fun stop() {
        processHandle.destroy()
    }

    /**
     * Kills the wrapper process immediately.
     */
    override fun kill() {
        processHandle.destroyForcibly()
    }

    /**
     * Registers a callback invoked once when the process exits.
     *
     * @param callback receives exit code `0` (the real code is unknown for
     *   adopted processes).
     */
    override fun onExit(callback: (Int) -> Unit) {
        processHandle.onExit().thenAccept { callback(0) }
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
     * Appends a command line to the workspace `console.in` file, which the
     * wrapper forwards into the server console.
     *
     * @param line the command, without a trailing newline.
     * @return `true` if written; `false` when the process has exited.
     */
    @Synchronized
    override fun sendCommand(line: String): Boolean {
        if (!processHandle.isAlive) {
            return false
        }
        return ConsoleInput.append(logFile.resolveSibling("console.in"), line)
    }
}
