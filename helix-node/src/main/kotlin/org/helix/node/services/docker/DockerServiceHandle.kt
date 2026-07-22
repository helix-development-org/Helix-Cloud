package org.helix.node.services.docker

import java.nio.file.Path
import org.helix.node.services.ConsoleInput
import org.helix.node.services.ServiceHandle

/**
 * [ServiceHandle] over a service container.
 *
 * Exit detection runs a blocking `docker wait` on a daemon thread; the
 * container is removed after it terminated.
 *
 * @property containerName name of the container.
 * @property runner CLI runner.
 * @property workspace host workspace bind-mounted into the container, used to
 *  deliver console input via the shared `console.in` file.
 */
class DockerServiceHandle(
    val containerName: String,
    private val runner: CommandRunner,
    private val workspace: Path,
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

    /**
     * Appends a command to the workspace `console.in`; the wrapper inside the
     * container tails the bind-mounted file and forwards it to the server.
     *
     * @param line the command, without a trailing newline.
     * @return `true` if written while the container is running.
     */
    override fun sendCommand(line: String): Boolean {
        if (!alive) {
            return false
        }
        return ConsoleInput.append(workspace.resolve("console.in"), line)
    }

    private fun fetchLogs(tail: Int): List<String> =
        runner.run(listOf("docker", "logs", "--tail", tail.toString(), containerName))
            .output.lines().filter { it.isNotEmpty() }

    private companion object {
        /** Lines captured from a terminated container. */
        const val FINAL_LOG_LINES = 100
    }
}
