package org.helix.node.services.docker

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
