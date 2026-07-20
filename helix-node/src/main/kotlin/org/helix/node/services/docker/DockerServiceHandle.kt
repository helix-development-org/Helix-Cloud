package org.helix.node.services.docker

import org.helix.node.services.ServiceHandle

/**
 * [ServiceHandle] over a service container.
 *
 * Exit detection runs a blocking `docker wait` on a daemon thread; the
 * container is removed after it terminated.
 *
 * @property containerName name of the container.
 * @property runner CLI runner.
 */
class DockerServiceHandle(
    val containerName: String,
    private val runner: CommandRunner,
) : ServiceHandle {
    @Volatile
    private var finalLogs: List<String>? = null

    /** Whether the container is running according to `docker inspect`. */
    override val alive: Boolean
        get() = runner.run(listOf("docker", "inspect", "-f", "{{.State.Running}}", containerName))
            .let { it.success() && it.output.trim() == "true" }

    /**
     * Stops the container gracefully with a 30 second timeout.
     */
    override fun stop() {
        runner.run(listOf("docker", "stop", "-t", "30", containerName))
    }

    /**
     * Kills the container immediately.
     */
    override fun kill() {
        runner.run(listOf("docker", "kill", containerName))
    }

    /**
     * Waits for container exit on a daemon thread.
     *
     * The final container output is captured before the container is
     * removed, so crash diagnostics stay available.
     *
     * @param callback receives the container exit code.
     */
    override fun onExit(callback: (Int) -> Unit) {
        val thread = Thread {
            val result = runner.run(listOf("docker", "wait", containerName))
            val exitCode = result.output.trim().toIntOrNull() ?: -1
            finalLogs = fetchLogs(FINAL_LOG_LINES)
            runner.run(listOf("docker", "rm", "-f", containerName))
            callback(exitCode)
        }
        thread.name = "docker-wait-$containerName"
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Reads the newest container log lines; after termination the output
     * captured before container removal is returned.
     *
     * @param tail maximum number of lines from the end.
     * @return the log lines, oldest first.
     */
    override fun logs(tail: Int): List<String> =
        finalLogs?.takeLast(tail) ?: fetchLogs(tail)

    private fun fetchLogs(tail: Int): List<String> =
        runner.run(listOf("docker", "logs", "--tail", tail.toString(), containerName))
            .output.lines().filter { it.isNotEmpty() }

    private companion object {
        /** Lines captured from a terminated container. */
        const val FINAL_LOG_LINES = 100
    }
}
