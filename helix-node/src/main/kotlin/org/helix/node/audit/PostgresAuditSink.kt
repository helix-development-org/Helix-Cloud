package org.helix.node.audit

import javax.sql.DataSource
import org.helix.api.audit.AuditEntry
import org.slf4j.LoggerFactory

/**
 * [AuditSink] persisting to the shared PostgreSQL `audit_log` table.
 *
 * @property dataSource shared pooled data source.
 */
class PostgresAuditSink(private val dataSource: DataSource) : AuditSink {
    private val logger = LoggerFactory.getLogger(PostgresAuditSink::class.java)

    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS audit_log (" +
                        "id BIGSERIAL PRIMARY KEY, epoch_ms BIGINT NOT NULL, category TEXT NOT NULL, " +
                        "actor TEXT NOT NULL, summary TEXT NOT NULL, outcome TEXT NOT NULL)",
                )
            }
        }
        logger.info("Audit log using PostgreSQL")
    }

    override fun append(entry: AuditEntry) {
        runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO audit_log (epoch_ms, category, actor, summary, outcome) " +
                        "VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setLong(1, entry.epochMs)
                    statement.setString(2, entry.category)
                    statement.setString(3, entry.actor)
                    statement.setString(4, entry.summary)
                    statement.setString(5, entry.outcome)
                    statement.executeUpdate()
                }
            }
        }.onFailure { logger.warn("Could not persist audit entry: {}", it.message) }
    }

    override fun loadRecent(limit: Int): List<AuditEntry> =
        runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT epoch_ms, category, actor, summary, outcome FROM audit_log " +
                        "ORDER BY id DESC LIMIT ?",
                ).use { statement ->
                    statement.setInt(1, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    AuditEntry(
                                        epochMs = rows.getLong(1),
                                        category = rows.getString(2),
                                        actor = rows.getString(3),
                                        summary = rows.getString(4),
                                        outcome = rows.getString(5),
                                    ),
                                )
                            }
                        }.asReversed()
                    }
                }
            }
        }.onFailure { logger.warn("Could not load audit history: {}", it.message) }
            .getOrDefault(emptyList())

    override fun prune(olderThanEpochMs: Long) {
        runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM audit_log WHERE epoch_ms < ?").use { statement ->
                    statement.setLong(1, olderThanEpochMs)
                    statement.executeUpdate()
                }
            }
        }.onFailure { logger.warn("Could not prune audit history: {}", it.message) }
    }
}
