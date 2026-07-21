package org.helix.node.config

/**
 * Node configuration loaded from `Helix/config/node.toml`.
 *
 * @property control settings of the control API and dashboard.
 * @property docker settings of the docker execution backend.
 * @property storage settings of the addon storage backend.
 * @property network display settings of the network as a whole.
 */
data class NodeConfig(
    val control: ControlSettings = ControlSettings(),
    val docker: DockerSettings = DockerSettings(),
    val storage: StorageSettings = StorageSettings(),
    val network: NetworkSettings = NetworkSettings(),
) {
    /**
     * Network-wide display settings.
     *
     * @property name display name used in disconnect screens (`{network}`).
     */
    data class NetworkSettings(
        val name: String = "our network",
    )

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
     * @property mode `json` (files per addon), `postgres` or `mongodb`
     *  (shared central database for addon storage and the audit log).
     * @property url connection string: a JDBC url for `postgres`
     *  (`jdbc:postgresql://host:5432/helix`) or a MongoDB connection string for
     *  `mongodb` (`mongodb://user:pass@host:27017`).
     * @property user database user (postgres; for mongodb prefer the URI).
     * @property password database password (postgres; for mongodb prefer the URI).
     * @property database database name used by `mongodb`.
     * @property poolSize maximum pooled connections.
     */
    data class StorageSettings(
        val mode: String = "json",
        val url: String = "jdbc:postgresql://127.0.0.1:5432/helix",
        val user: String = "helix",
        val password: String = "helix",
        val database: String = "helix",
        val poolSize: Int = 8,
    ) {
        /**
         * Whether the postgres backend is selected.
         *
         * @return `true` for `postgres` mode.
         */
        fun isPostgres(): Boolean = mode.equals("postgres", ignoreCase = true)

        /**
         * Whether the MongoDB backend is selected.
         *
         * @return `true` for `mongodb` mode.
         */
        fun isMongo(): Boolean = mode.equals("mongodb", ignoreCase = true) || mode.equals("mongo", ignoreCase = true)
    }
}
