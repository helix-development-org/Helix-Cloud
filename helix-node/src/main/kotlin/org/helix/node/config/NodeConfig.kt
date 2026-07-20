package org.helix.node.config

/**
 * Node configuration loaded from `Helix/config/node.toml`.
 *
 * @property control settings of the control API and dashboard.
 * @property docker settings of the docker execution backend.
 * @property storage settings of the addon storage backend.
 */
data class NodeConfig(
    val control: ControlSettings = ControlSettings(),
    val docker: DockerSettings = DockerSettings(),
    val storage: StorageSettings = StorageSettings(),
) {
    /**
     * Control API settings.
     *
     * @property host interface the control API binds to.
     * @property port port the control API listens on.
     * @property token static admin token; still accepted as a bootstrap login
     *  and used by bridges/wrappers for machine-to-machine auth.
     * @property loginPermission permission a player must hold to sign in to the
     *  web panel via their Minecraft account.
     * @property codeTtlSeconds how long an issued in-game login code stays valid.
     * @property sessionTtlSeconds how long a web session stays valid.
     * @property loginMessage in-game message sent with the login code;
     *  `{code}` is replaced with the generated code.
     */
    data class ControlSettings(
        val host: String = "127.0.0.1",
        val port: Int = 8080,
        val token: String = "dev-token-change-me",
        val loginPermission: String = "helix.panel.login",
        val codeTtlSeconds: Long = 300,
        val sessionTtlSeconds: Long = 86_400,
        val loginMessage: String = "§b§lHelix §r§7» §fYour panel login code is §b{code}§7. It expires in 5 minutes.",
    )

    /**
     * Docker execution settings.
     *
     * @property network docker network all Helix containers join.
     * @property image base image used to run service containers.
     */
    data class DockerSettings(
        val network: String = "helix",
        val image: String = "eclipse-temurin:24-jre",
    )

    /**
     * Addon storage settings.
     *
     * @property mode `json` (files per addon) or `postgres` (shared db).
     * @property url JDBC url, for example `jdbc:postgresql://host:5432/helix`.
     * @property user database user.
     * @property password database password.
     * @property poolSize maximum pooled connections.
     */
    data class StorageSettings(
        val mode: String = "json",
        val url: String = "jdbc:postgresql://127.0.0.1:5432/helix",
        val user: String = "helix",
        val password: String = "helix",
        val poolSize: Int = 8,
    ) {
        /**
         * Whether the postgres backend is selected.
         *
         * @return `true` for `postgres` mode.
         */
        fun isPostgres(): Boolean = mode.equals("postgres", ignoreCase = true)
    }
}
