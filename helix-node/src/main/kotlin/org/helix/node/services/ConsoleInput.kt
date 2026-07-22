package org.helix.node.services

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Appends console commands to a service's `console.in` file. The wrapper —
 * running with the workspace as its working directory (also inside docker,
 * where the workspace is bind-mounted) — tails this file and forwards the
 * lines into the server console. This makes the panel console work uniformly
 * for process and docker services.
 */
object ConsoleInput {
    /**
     * Appends a single command line (with a trailing newline).
     *
     * @param file the workspace `console.in` path.
     * @param line the command, without a trailing newline.
     * @return `true` if the line was written.
     */
    fun append(file: Path, line: String): Boolean =
        runCatching {
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.isSuccess
}
