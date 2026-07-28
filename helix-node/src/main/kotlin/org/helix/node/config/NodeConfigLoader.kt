package org.helix.node.config

import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml

/**
 * Loads [NodeConfig] from `config/node.toml` below the data directory.
 */
class NodeConfigLoader {
    /**
     * Parses the node configuration, falling back to defaults for missing
     * keys and a missing file.
     *
     * @param dataDirectory the `Helix/` data directory root.
     * @return the effective node configuration.
     * @throws IllegalArgumentException if the file contains syntax errors.
     */
    fun load(dataDirectory: Path): NodeConfig {
        val file = dataDirectory.resolve("config/node.toml")
        if (Files.notExists(file)) {
            return NodeConfig()
        }
        val toml = Toml.parse(file)
        require(!toml.hasErrors()) {
            "invalid node.toml: ${toml.errors().joinToString { it.toString() }}"
        }
        val defaults = NodeConfig()
        return NodeConfig(
            control = NodeConfig.ControlSettings(
                host = toml.getString("control.host") ?: defaults.control.host,
                port = toml.getLong("control.port")?.toInt() ?: defaults.control.port,
                token = toml.getString("control.token") ?: defaults.control.token,
                loginPermission = toml.getString("control.loginPermission")
                    ?: defaults.control.loginPermission,
                codeTtlSeconds = toml.getLong("control.codeTtlSeconds") ?: defaults.control.codeTtlSeconds,
                sessionTtlSeconds = toml.getLong("control.sessionTtlSeconds")
                    ?: defaults.control.sessionTtlSeconds,
                idleTimeoutSeconds = toml.getLong("control.idleTimeoutSeconds")
                    ?: defaults.control.idleTimeoutSeconds,
                loginMessage = toml.getString("control.loginMessage") ?: defaults.control.loginMessage,
                tlsKeystore = toml.getString("control.tlsKeystore") ?: defaults.control.tlsKeystore,
                tlsKeystorePassword = toml.getString("control.tlsKeystorePassword")
                    ?: defaults.control.tlsKeystorePassword,
                tlsKeyAlias = toml.getString("control.tlsKeyAlias") ?: defaults.control.tlsKeyAlias,
            ),
            docker = NodeConfig.DockerSettings(
                network = toml.getString("docker.network") ?: defaults.docker.network,
                image = toml.getString("docker.image") ?: defaults.docker.image,
            ),
            storage = NodeConfig.StorageSettings(
                mode = toml.getString("storage.mode") ?: defaults.storage.mode,
                url = toml.getString("storage.url") ?: defaults.storage.url,
                user = toml.getString("storage.user") ?: defaults.storage.user,
                password = toml.getString("storage.password") ?: defaults.storage.password,
                database = toml.getString("storage.database") ?: defaults.storage.database,
                poolSize = toml.getLong("storage.poolSize")?.toInt() ?: defaults.storage.poolSize,
            ),
            network = NodeConfig.NetworkSettings(
                name = toml.getString("network.name") ?: defaults.network.name,
            ),
            proxy = NodeConfig.ProxySettings(
                forwardingSecret = toml.getString("proxy.forwardingSecret") ?: defaults.proxy.forwardingSecret,
                legacyForwarding = toml.getBoolean("proxy.legacyForwarding") ?: defaults.proxy.legacyForwarding,
            ),
            eula = NodeConfig.EulaSettings(
                accept = toml.getBoolean("eula.accept") ?: defaults.eula.accept,
                acceptedBy = toml.getString("eula.acceptedBy") ?: defaults.eula.acceptedBy,
            ),
        )
    }
}
