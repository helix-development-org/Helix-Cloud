package org.helix.node.storage

import java.nio.file.Path
import org.bson.Document
import org.helix.node.audit.AuditSink
import org.helix.node.audit.FileAuditSink
import org.helix.node.audit.MongoAuditSink
import org.helix.node.audit.PostgresAuditSink
import org.helix.node.config.NodeConfig
import org.slf4j.LoggerFactory

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
        private val logger = LoggerFactory.getLogger(StorageBackend::class.java)

        /** Connection attempts before giving up and letting boot fail. */
        private const val MAX_ATTEMPTS = 10

        /** First retry delay; doubles every attempt up to [MAX_BACKOFF_MS]. */
        private const val INITIAL_BACKOFF_MS = 1_000L

        /** Retry delay never grows past this. */
        private const val MAX_BACKOFF_MS = 30_000L

        /**
         * Builds the backend for the configured storage mode.
         *
         * A `postgres`/`mongodb` connection failure at boot (the database not
         * up yet — the common single-host ordering problem) is retried with
         * exponential backoff instead of crashing the node immediately.
         *
         * @param settings the storage settings.
         * @param auditFile the JSONL audit file used in file mode.
         * @return a ready backend owning any database resource.
         */
        fun create(settings: NodeConfig.StorageSettings, auditFile: Path): StorageBackend = when {
            settings.isPostgres() -> {
                val pool = connectWithRetry("PostgreSQL", settings.url) { PostgresPool.create(settings) }
                StorageBackend(PostgresStorageProvider(pool), PostgresAuditSink(pool), pool)
            }
            settings.isMongo() -> {
                val client = connectWithRetry("MongoDB", settings.url) {
                    MongoFactory.create(settings).also {
                        // The driver connects lazily — ping now so an unreachable
                        // server is caught (and retried) here, instead of
                        // surfacing later on the first addon read/write deep
                        // inside the running node.
                        it.getDatabase(settings.database).runCommand(Document("ping", 1))
                    }
                }
                val database = client.getDatabase(settings.database)
                StorageBackend(MongoStorageProvider(database), MongoAuditSink(database), client)
            }
            else -> StorageBackend(JsonStorageProvider(), FileAuditSink(auditFile), null)
        }

        /**
         * Retries [connect] with exponential backoff, for the boot-time race
         * against a not-yet-ready database.
         *
         * @param name backend name used in log messages.
         * @param target connection string logged alongside retries.
         * @param sleep the backoff delay function, injectable for tests.
         * @param connect the connection attempt.
         * @return the connected resource.
         * @throws Exception the last failure, once [MAX_ATTEMPTS] is exhausted.
         */
        internal fun <T> connectWithRetry(
            name: String,
            target: String,
            sleep: (Long) -> Unit = Thread::sleep,
            connect: () -> T,
        ): T {
            var backoffMs = INITIAL_BACKOFF_MS
            var attempt = 1
            while (true) {
                try {
                    return connect()
                } catch (ex: Exception) {
                    if (attempt >= MAX_ATTEMPTS) {
                        logger.error(
                            "Could not connect to {} storage at {} after {} attempts — giving up",
                            name,
                            target,
                            attempt,
                        )
                        throw ex
                    }
                    logger.warn(
                        "Could not connect to {} storage at {} (attempt {}/{}) — retrying in {}s: {}",
                        name,
                        target,
                        attempt,
                        MAX_ATTEMPTS,
                        backoffMs / 1000,
                        ex.message,
                    )
                    sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    attempt++
                }
            }
        }
    }
}
