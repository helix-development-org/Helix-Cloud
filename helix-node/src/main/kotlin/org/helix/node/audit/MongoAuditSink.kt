package org.helix.node.audit

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Sorts
import org.bson.Document
import org.helix.api.audit.AuditEntry
import org.slf4j.LoggerFactory

/**
 * [AuditSink] persisting to the shared MongoDB `audit_log` collection.
 *
 * Entries are ordered by their monotonically increasing `_id`, so the newest
 * are read back first and returned chronologically.
 *
 * @param database shared MongoDB database.
 */
class MongoAuditSink(database: MongoDatabase) : AuditSink {
    private val logger = LoggerFactory.getLogger(MongoAuditSink::class.java)
    private val collection = database.getCollection("audit_log")

    init {
        logger.info("Audit log using MongoDB")
    }

    override fun append(entry: AuditEntry) {
        runCatching {
            collection.insertOne(
                Document("epochMs", entry.epochMs)
                    .append("category", entry.category)
                    .append("actor", entry.actor)
                    .append("summary", entry.summary)
                    .append("outcome", entry.outcome),
            )
        }.onFailure { logger.warn("Could not persist audit entry: {}", it.message) }
    }

    override fun loadRecent(limit: Int): List<AuditEntry> =
        runCatching {
            collection.find()
                .sort(Sorts.descending("_id"))
                .limit(limit)
                .map { document ->
                    AuditEntry(
                        epochMs = document.getLong("epochMs") ?: 0L,
                        category = document.getString("category") ?: "",
                        actor = document.getString("actor") ?: "",
                        summary = document.getString("summary") ?: "",
                        outcome = document.getString("outcome") ?: "",
                    )
                }
                .toList()
                .asReversed()
        }.onFailure { logger.warn("Could not load audit history: {}", it.message) }
            .getOrDefault(emptyList())
}
