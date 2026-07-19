package org.helix.node.config

/**
 * Node configuration loaded from `Helix/config/node.toml`.
 *
 * @property control settings of the control API and dashboard.
 * @property docker settings of the docker execution backend.
 */
data class NodeConfig(
    val control: ControlSettings = ControlSettings(),
    val docker: DockerSettings = DockerSettings(),
) {
    /**
     * Control API settings.
     *
     * @property host interface the control API binds to.
     * @property port port the control API listens on.
     * @property token bearer token required by all authenticated endpoints.
     */
    data class ControlSettings(
        val host: String = "127.0.0.1",
        val port: Int = 8080,
        val token: String = "dev-token-change-me",
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
}
