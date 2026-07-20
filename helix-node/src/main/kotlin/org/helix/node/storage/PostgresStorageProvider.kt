package org.helix.node.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Path
import org.helix.api.storage.AddonStorage
import org.helix.node.config.NodeConfig
import org.slf4j.LoggerFactory

/**
 * [StorageProvider] for the `postgres` mode: all addons share one pooled
 * PostgreSQL database and the `addon_storage` table.
 *
 * @property settings database connection settings.
 */
class PostgresStorageProvider(settings: NodeConfig.StorageSettings) : StorageProvider {
    private val logger = LoggerFactory.getLogger(PostgresStorageProvider::class.java)
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = settings.url
            username = settings.user
            password = settings.password
            maximumPoolSize = settings.poolSize
            poolName = "helix-storage"
        },
    )

    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS addon_storage (" +
                        "addon_id TEXT NOT NULL, doc_key TEXT NOT NULL, value TEXT NOT NULL, " +
                        "PRIMARY KEY (addon_id, doc_key))",
                )
            }
        }
        logger.info("Addon storage using PostgreSQL at {}", settings.url)
    }

    override fun forAddon(addonId: String, dataDirectory: Path): AddonStorage =
        PostgresAddonStorage(dataSource, addonId)

    override fun close() {
        dataSource.close()
    }
}
