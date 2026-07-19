package org.helix.node.launcher

import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates the `Helix/` data directory layout and default configuration files
 * on first start. Existing files are never overwritten.
 *
 * @property root the data directory root, usually `Helix/` next to the jar.
 */
class HelixDirectoryInitializer(private val root: Path) {
    /**
     * Ensures the directory layout and default configuration exist.
     *
     * @return the data directory root.
     */
    fun initialize(): Path {
        DIRECTORIES.forEach { Files.createDirectories(root.resolve(it)) }
        DEFAULT_FILES.forEach { (relativePath, content) ->
            val target = root.resolve(relativePath)
            if (Files.notExists(target)) {
                Files.writeString(target, content)
            }
        }
        return root
    }

    private companion object {
        /** Directories created below the data directory root. */
        val DIRECTORIES = listOf("config", "tasks", "templates", "services", "addons")

        /** Default configuration files written on first start. */
        val DEFAULT_FILES = mapOf(
            "config/node.toml" to """
                # Helix-Cloud node configuration.

                [control]
                host = "127.0.0.1"
                port = 8080
                token = "dev-token-change-me"
            """.trimIndent() + "\n",
            "config/versions.toml" to """
                # Maps environment + version to a download source.
                # New server versions are configuration, not code.

                [[paper]]
                version = "1.21.11"

                [[velocity]]
                version = "3.4.0"
            """.trimIndent() + "\n",
        )
    }
}
