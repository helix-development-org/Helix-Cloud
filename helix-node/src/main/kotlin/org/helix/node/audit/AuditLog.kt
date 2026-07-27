package org.helix.node.audit

import java.nio.file.Path
import org.helix.api.audit.AuditEntry

/**
 * Complete, durable audit trail.
 *
 * Every recorded entry is appended to the configured [AuditSink] (a JSONL
 * file or the shared PostgreSQL database) and kept in an in-memory ring
 * buffer for fast dashboard/API reads. On startup the tail of the sink is
 * loaded back so the trail survives restarts.
 *
 * @property sink durable backend the trail is written to and read from.
 * @property capacity in-memory ring buffer size.
 * @property clock epoch millis source, injectable for tests.
 */
class AuditLog(
    private val sink: AuditSink,
    private val capacity: Int = 5000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = ArrayDeque<AuditEntry>()

    /**
     * Convenience constructor persisting to a JSONL file.
     *
     * @param file the `audit.jsonl` path.
     */
    constructor(file: Path) : this(FileAuditSink(file))

    init {
        sink.loadRecent(capacity).forEach { entries.addLast(it) }
    }

    /**
     * Records an audit entry, persisting it and updating the ring buffer.
     *
     * @param category coarse grouping.
     * @param actor who caused it.
     * @param summary human readable description.
     * @param outcome `ok`, `denied`, `error` or `info`.
     */
    @Synchronized
    fun record(category: String, actor: String, summary: String, outcome: String = "ok") {
        val entry = AuditEntry(clock(), category, actor, summary, outcome)
        entries.addLast(entry)
        while (entries.size > capacity) {
            entries.removeFirst()
        }
        sink.append(entry)
    }

    /**
     * Returns the newest entries first, optionally filtered by category,
     * actor and/or a free-text search across the summary (action name,
     * affected player, service id — whatever the invocation carried).
     *
     * @param limit maximum number of entries.
     * @param category category to filter by, or `null` for all.
     * @param actor actor substring to filter by (case-insensitive), or `null` for all.
     * @param search summary substring to filter by (case-insensitive), or `null` for all.
     * @return matching entries, newest first.
     */
    @Synchronized
    fun recent(limit: Int, category: String? = null, actor: String? = null, search: String? = null): List<AuditEntry> =
        entries.toList()
            .let { list -> if (category == null) list else list.filter { it.category == category } }
            .let { list -> if (actor.isNullOrBlank()) list else list.filter { it.actor.contains(actor, ignoreCase = true) } }
            .let { list -> if (search.isNullOrBlank()) list else list.filter { it.summary.contains(search, ignoreCase = true) } }
            .takeLast(limit)
            .asReversed()
}
