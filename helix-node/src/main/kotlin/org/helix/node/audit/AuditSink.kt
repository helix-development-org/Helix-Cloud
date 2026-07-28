package org.helix.node.audit

import org.helix.api.audit.AuditEntry

/**
 * Durable backend the [AuditLog] appends to and reloads from.
 */
interface AuditSink {
    /**
     * Appends one entry durably.
     *
     * @param entry the audit entry.
     */
    fun append(entry: AuditEntry)

    /**
     * Loads the most recent entries, oldest first.
     *
     * @param limit maximum number of entries.
     * @return entries in chronological order.
     */
    fun loadRecent(limit: Int): List<AuditEntry>

    /**
     * Durably deletes every entry older than a cutoff, enforcing the
     * configured hard retention cap so the trail does not grow forever.
     *
     * @param olderThanEpochMs cutoff; entries strictly older are removed.
     */
    fun prune(olderThanEpochMs: Long)
}
