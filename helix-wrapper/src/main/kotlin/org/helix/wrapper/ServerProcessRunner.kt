package org.helix.wrapper

import java.util.concurrent.TimeUnit

/**
 * Runs the wrapped server process and ties its lifetime to the wrapper.
 *
 * IO is inherited so server output reaches the wrapper's stdout, where the
 * node (or docker) captures it. A shutdown hook forwards termination to the
 * server so `SIGTERM` on the wrapper stops the server gracefully.
 */
class ServerProcessRunner {
    /**
     * Starts the command and blocks until it exits.
     *
     * @param command full server command line.
     * @return the server exit code.
     */
    fun run(command: List<String>): Int {
        val process = ProcessBuilder(command).inheritIO().start()
        val hook = Thread {
            process.destroy()
            if (!process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        val exitCode = process.waitFor()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        return exitCode
    }

    private companion object {
        /** Seconds the server gets to stop before it is killed. */
        const val GRACEFUL_STOP_SECONDS = 30L
    }
}
