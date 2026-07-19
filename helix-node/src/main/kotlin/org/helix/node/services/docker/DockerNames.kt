package org.helix.node.services.docker

/**
 * Naming conventions for Helix docker resources.
 */
object DockerNames {
    /**
     * Container name of a service.
     *
     * The name doubles as in-network hostname, so docker proxies reach
     * docker backends via `containerName:port`.
     *
     * @param serviceId the service id, for example `Lobby-1`.
     * @return the container name, for example `helix-lobby-1`.
     */
    fun containerName(serviceId: String): String = "helix-${serviceId.lowercase()}"
}
