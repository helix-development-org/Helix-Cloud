package org.helix.wrapper

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the wrapped server process and ties its lifetime to the wrapper.
 *
 * Server output is inherited so it reaches the wrapper's stdout, where the node
 * (or docker) captures it. Server input is a pipe fed by a [ConsoleForwarder]
 * that streams the workspace `console.in` file into the server console, so the
 * web-panel console works for both process and docker services. A shutdown hook
 * forwards termination to the server so `SIGTERM` stops it gracefully.
 */
class ServerProcessRunner {
    /**
     * Starts the command and blocks until it exits.
     *
     * @param command full server command line.
     * @param consoleFile console-input file forwarded into the server, or
     *  `null` to disable console forwarding (used by tests).
     * @return the server exit code.
     */
    fun run(command: List<String>, consoleFile: Path? = null): Int {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val consoleThread = consoleFile?.let {
            ConsoleForwarder(it, process.outputStream).startPumping(alive = process::isAlive)
        }
        val hook = Thread {
            process.destroy()
            if (!process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        val exitCode = process.waitFor()
        consoleThread?.interrupt()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        return exitCode
    }

    private companion object {
        /** Seconds the server gets to stop before it is killed. */
        const val GRACEFUL_STOP_SECONDS = 30L
    }
}
