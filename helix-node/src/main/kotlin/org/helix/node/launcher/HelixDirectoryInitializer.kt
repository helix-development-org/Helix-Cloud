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
        val DIRECTORIES = listOf(
            "config",
            "tasks",
            "templates",
            "services/static",
            "services/temp",
            "cache",
            "addons",
        )

        /** Default configuration files written on first start. */
        val DEFAULT_FILES = mapOf(
            "config/node.toml" to """
                # Helix-Cloud node configuration.
                # Every key is optional; missing keys fall back to these defaults.

                [control]
                host = "127.0.0.1"                     # bind interface of control API + dashboard
                port = 8080
                token = "dev-token-change-me"          # admin token: full access, bridges/wrappers, panel emergency login
                loginPermission = "helix.panel.login"  # permission required for the panel Minecraft login
                codeTtlSeconds = 300                   # validity of the in-game login code
                sessionTtlSeconds = 86400              # validity of a web session
                loginMessage = "§b§lHelix §r§7» §fYour panel login code is §b{code}§7. It expires in 5 minutes."
                tlsKeystore = ""                       # path to a PKCS12 keystore -> enables HTTPS
                tlsKeystorePassword = ""
                tlsKeyAlias = "helix"

                [docker]
                network = "helix"                      # docker network joined by all Helix containers
                image = "eclipse-temurin:24-jre"       # base image for service containers

                [storage]
                mode = "json"                          # "json" (files), "postgres" or "mongodb"
                url = "jdbc:postgresql://127.0.0.1:5432/helix"  # or "mongodb://user:pass@host:27017"
                user = "helix"                         # postgres (for mongodb prefer the URI)
                password = "helix"
                database = "helix"                     # database name (mongodb)
                poolSize = 8

                [network]
                name = "our network"                   # initial display name ({network}); editable in the panel afterwards
            """.trimIndent() + "\n",
            "config/versions.toml" to """
                # Maps environment + version to a download source.
                # New server versions are configuration, not code.
                # Jars resolve via the PaperMC Fill API; an optional
                # url = "https://..." per entry overrides the download.

                [[paper]]
                version = "1.21.11"

                [[velocity]]
                version = "3.4.0"
            """.trimIndent() + "\n",
        )
    }
}
