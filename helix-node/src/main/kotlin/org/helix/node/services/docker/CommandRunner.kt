package org.helix.node.services.docker

/**
 * Result of an executed CLI command.
 *
 * @property exitCode process exit code.
 * @property output combined stdout and stderr text.
 */
data class CommandResult(
    val exitCode: Int,
    val output: String,
) {
    /**
     * Whether the command succeeded.
     *
     * @return `true` for exit code 0.
     */
    fun success(): Boolean = exitCode == 0
}

/**
 * Executes CLI commands, abstracted for offline tests.
 */
fun interface CommandRunner {
    /**
     * Runs a command and waits for it to finish.
     *
     * @param command the command and its arguments.
     * @return exit code and combined output.
     */
    fun run(command: List<String>): CommandResult
}

/**
 * [CommandRunner] backed by [ProcessBuilder].
 */
class SystemCommandRunner : CommandRunner {
    /**
     * Runs a command and waits for it to finish.
     *
     * @param command the command and its arguments.
     * @return exit code and combined output.
     */
    override fun run(command: List<String>): CommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return CommandResult(process.waitFor(), output)
    }
}
