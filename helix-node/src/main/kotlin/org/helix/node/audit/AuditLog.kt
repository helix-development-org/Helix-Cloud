package org.helix.node.audit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import org.helix.api.audit.AuditEntry
import org.slf4j.LoggerFactory

/**
 * Complete, durable audit trail.
 *
 * Every recorded entry is appended to `Helix/audit/audit.jsonl` (one JSON
 * object per line, append-only) and kept in an in-memory ring buffer for
 * fast dashboard/API reads. On startup the tail of the file is loaded back
 * so the trail survives restarts.
 *
 * @property file the `audit.jsonl` path.
 * @property capacity in-memory ring buffer size.
 * @property clock epoch millis source, injectable for tests.
 */
class AuditLog(
    private val file: Path,
    private val capacity: Int = 5000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(AuditLog::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val entries = ArrayDeque<AuditEntry>()

    init {
        if (Files.exists(file)) {
            runCatching {
                Files.readAllLines(file).takeLast(capacity).forEach { line ->
                    if (line.isNotBlank()) {
                        entries.addLast(json.decodeFromString<AuditEntry>(line))
                    }
                }
            }.onFailure { logger.warn("Could not load audit history: {}", it.message) }
        }
    }

    /**
     * Records an audit entry, appending it to disk and the ring buffer.
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
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                json.encodeToString(entry) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure { logger.warn("Could not persist audit entry: {}", it.message) }
    }

    /**
     * Returns the newest entries first, optionally filtered by category.
     *
     * @param limit maximum number of entries.
     * @param category category to filter by, or `null` for all.
     * @return matching entries, newest first.
     */
    @Synchronized
    fun recent(limit: Int, category: String? = null): List<AuditEntry> =
        entries.toList()
            .let { list -> if (category == null) list else list.filter { it.category == category } }
            .takeLast(limit)
            .asReversed()
}
