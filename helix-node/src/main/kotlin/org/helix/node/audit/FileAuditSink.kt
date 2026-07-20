package org.helix.node.audit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import org.helix.api.audit.AuditEntry
import org.slf4j.LoggerFactory

/**
 * [AuditSink] appending to `audit.jsonl`, one JSON object per line.
 *
 * @property file the `audit.jsonl` path.
 */
class FileAuditSink(private val file: Path) : AuditSink {
    private val logger = LoggerFactory.getLogger(FileAuditSink::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override fun append(entry: AuditEntry) {
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

    override fun loadRecent(limit: Int): List<AuditEntry> {
        if (Files.notExists(file)) {
            return emptyList()
        }
        return runCatching {
            Files.readAllLines(file).takeLast(limit)
                .filter { it.isNotBlank() }
                .map { json.decodeFromString<AuditEntry>(it) }
        }.onFailure { logger.warn("Could not load audit history: {}", it.message) }
            .getOrDefault(emptyList())
    }
}
