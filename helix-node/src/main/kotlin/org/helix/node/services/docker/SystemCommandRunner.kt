package org.helix.node.services.docker

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
