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

    /** OS process id of the wrapper. */
    override val pid: Long
        get() = process.pid()

    /** OS start instant of the wrapper, when the platform reports it. */
    override val startInstantEpochMs: Long?
        get() = runCatching { process.info().startInstant().orElse(null)?.toEpochMilli() }.getOrNull()

    /**
     * Requests a graceful stop by terminating the wrapper, which forwards
     * the termination to the server.
     */
    override fun stop() {
        process.destroy()
    }

    /**
     * Kills the wrapper process and its whole process tree immediately.
     *
     * A force-killed wrapper never runs its shutdown hook, so the server
     * child it spawned would survive as an orphan still holding the
     * service port — the descendants are therefore killed explicitly.
     * (Graceful [stop] keeps relying on the wrapper's hook.)
     */
    override fun kill() {
        process.descendants().forEach { it.destroyForcibly() }
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
     * Appends a command line to the workspace `console.in` file, which the
     * wrapper forwards into the server console.
     *
     * @param line the command, without a trailing newline.
     * @return `true` if written; `false` when the process has exited.
     */
    @Synchronized
    override fun sendCommand(line: String): Boolean {
        if (!process.isAlive) {
            return false
        }
        return ConsoleInput.append(logFile.resolveSibling("console.in"), line)
    }
}
