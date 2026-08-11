package org.helix.node.storage

import org.helix.api.storage.AddonStorage
import org.slf4j.LoggerFactory
import java.nio.file.Path
import javax.sql.DataSource

/**
 * [StorageProvider] for the `postgres` mode: all addons share one pooled
 * PostgreSQL database and the `addon_storage` table.
 *
 * The connection pool is owned externally (shared with the audit log) and
 * is not closed here.
 *
 * @property dataSource shared pooled data source.
 */
class PostgresStorageProvider(private val dataSource: DataSource) : StorageProvider {
    private val logger = LoggerFactory.getLogger(PostgresStorageProvider::class.java)

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
        logger.info("Addon storage using PostgreSQL")
    }

    override fun forAddon(addonId: String, dataDirectory: Path): AddonStorage =
        PostgresAddonStorage(dataSource, addonId)
}
