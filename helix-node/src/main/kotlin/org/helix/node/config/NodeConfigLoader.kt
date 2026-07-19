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
            ),
            docker = NodeConfig.DockerSettings(
                network = toml.getString("docker.network") ?: defaults.docker.network,
                image = toml.getString("docker.image") ?: defaults.docker.image,
            ),
        )
    }
}
