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
