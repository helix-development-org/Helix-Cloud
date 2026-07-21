package org.helix.node.storage

import java.nio.file.Path
import org.helix.node.audit.AuditSink
import org.helix.node.audit.FileAuditSink
import org.helix.node.audit.MongoAuditSink
import org.helix.node.audit.PostgresAuditSink
import org.helix.node.config.NodeConfig

/**
 * The node's chosen storage backend: it owns the shared database resource (a
 * connection pool or MongoDB client) and exposes both the addon
 * [StorageProvider] and the audit [AuditSink] wired to it, so `postgres` and
 * `mongodb` each open exactly one connection pool shared by both.
 *
 * @property storageProvider addon document storage for the selected mode.
 * @property auditSink durable audit backend for the selected mode.
 */
class StorageBackend private constructor(
    val storageProvider: StorageProvider,
    val auditSink: AuditSink,
    private val resource: AutoCloseable?,
) : AutoCloseable {
    /** Closes the owned database resource, if any. */
    override fun close() {
        resource?.close()
    }

    /** Selects and constructs the backend for the configured storage mode. */
    companion object {
        /**
         * Builds the backend for the configured storage mode.
         *
         * @param settings the storage settings.
         * @param auditFile the JSONL audit file used in file mode.
         * @return a ready backend owning any database resource.
         */
        fun create(settings: NodeConfig.StorageSettings, auditFile: Path): StorageBackend = when {
            settings.isPostgres() -> {
                val pool = PostgresPool.create(settings)
                StorageBackend(PostgresStorageProvider(pool), PostgresAuditSink(pool), pool)
            }
            settings.isMongo() -> {
                val client = MongoFactory.create(settings)
                val database = client.getDatabase(settings.database)
                StorageBackend(MongoStorageProvider(database), MongoAuditSink(database), client)
            }
            else -> StorageBackend(JsonStorageProvider(), FileAuditSink(auditFile), null)
        }
    }
}
