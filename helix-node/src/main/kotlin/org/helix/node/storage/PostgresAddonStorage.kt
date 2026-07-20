package org.helix.node.storage

import javax.sql.DataSource
import org.helix.api.storage.AddonStorage

/**
 * [AddonStorage] backed by a shared PostgreSQL table
 * `addon_storage(addon_id, doc_key, value)` — the `postgres` storage mode.
 *
 * @property dataSource pooled database connection source.
 * @property addonId owning addon id, isolating this addon's documents.
 */
class PostgresAddonStorage(
    private val dataSource: DataSource,
    private val addonId: String,
) : AddonStorage {
    override fun read(key: String): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT value FROM addon_storage WHERE addon_id = ? AND doc_key = ?",
            ).use { statement ->
                statement.setString(1, addonId)
                statement.setString(2, key)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        }

    override fun write(key: String, value: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO addon_storage (addon_id, doc_key, value) VALUES (?, ?, ?) " +
                    "ON CONFLICT (addon_id, doc_key) DO UPDATE SET value = EXCLUDED.value",
            ).use { statement ->
                statement.setString(1, addonId)
                statement.setString(2, key)
                statement.setString(3, value)
                statement.executeUpdate()
            }
        }
    }

    override fun delete(key: String): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM addon_storage WHERE addon_id = ? AND doc_key = ?",
            ).use { statement ->
                statement.setString(1, addonId)
                statement.setString(2, key)
                statement.executeUpdate() > 0
            }
        }

    override fun keys(): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT doc_key FROM addon_storage WHERE addon_id = ?",
            ).use { statement ->
                statement.setString(1, addonId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
}
