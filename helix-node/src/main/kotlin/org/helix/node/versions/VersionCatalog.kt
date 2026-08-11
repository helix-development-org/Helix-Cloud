package org.helix.node.versions

import org.helix.api.environment.Environment
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Available platform versions loaded from `config/versions.toml`.
 *
 * New server versions are a config entry, not a code change.
 *
 * @property entries all configured versions.
 */
class VersionCatalog(val entries: List<VersionEntry>) {
    /**
     * Looks up an entry.
     *
     * @param environment platform to look up.
     * @param version version string to look up.
     * @return the entry or `null` if not configured.
     */
    fun find(environment: Environment, version: String): VersionEntry? =
        entries.firstOrNull { it.environment == environment && it.version == version }

    /**
     * Default version of an environment, the first configured entry.
     *
     * @param environment platform to look up.
     * @return the default entry or `null` if none configured.
     */
    fun default(environment: Environment): VersionEntry? =
        entries.firstOrNull { it.environment == environment }

    companion object {
        /**
         * Parses `config/versions.toml` below the data directory.
         *
         * @param dataDirectory the `Helix/` data directory root.
         * @return the catalog; empty when the file is missing.
         * @throws IllegalArgumentException on syntax errors.
         */
        fun load(dataDirectory: Path): VersionCatalog {
            val file = dataDirectory.resolve("config/versions.toml")
            if (Files.notExists(file)) {
                return VersionCatalog(emptyList())
            }
            val toml = Toml.parse(file)
            require(!toml.hasErrors()) {
                "invalid versions.toml: ${toml.errors().joinToString { it.toString() }}"
            }
            val entries = Environment.entries.flatMap { environment ->
                val tables = toml.getArray(environment.name.lowercase()) ?: return@flatMap emptyList()
                (0 until tables.size()).map { index ->
                    val table = tables.getTable(index)
                    VersionEntry(
                        environment = environment,
                        version = requireNotNull(table.getString("version")) {
                            "versions.toml: ${environment.name.lowercase()} entry $index misses 'version'"
                        },
                        url = table.getString("url"),
                    )
                }
            }
            return VersionCatalog(entries)
        }
    }
}
