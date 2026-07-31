package org.helix.node.audit

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import org.helix.api.audit.AuditEntry
import org.slf4j.LoggerFactory

/**
 * [AuditSink] appending to `audit.jsonl`, one JSON object per line.
 *
 * The file rolls to a dated sibling once it passes [maxFileSizeBytes], so a
 * long-lived node does not grow one unbounded file; [loadRecent] only ever
 * reads the tail of the *current* file, from the end backward, so a
 * multi-gigabyte history never has to be loaded into memory just to recover
 * the last few entries at boot.
 *
 * @property file the `audit.jsonl` path.
 * @property maxFileSizeBytes size past which the file rotates on the next append.
 */
class FileAuditSink(
    private val file: Path,
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
) : AuditSink {
    private val logger = LoggerFactory.getLogger(FileAuditSink::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun append(entry: AuditEntry) {
        runCatching {
            Files.createDirectories(file.parent)
            rotateIfNeeded()
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
        return runCatching { decodeLines(tailLines(file, limit)) }
            .onFailure { logger.warn("Could not load audit history: {}", it.message) }
            .getOrDefault(emptyList())
    }

    // Shares append's monitor: prune rewrites the file via a temp copy, so an unsynchronized
    // append could land between reading the lines and the replacing move — and be lost.
    @Synchronized
    override fun prune(olderThanEpochMs: Long) {
        if (Files.notExists(file)) {
            return
        }
        runCatching {
            val kept = Files.readAllLines(file)
                .filter { it.isNotBlank() && json.decodeFromString<AuditEntry>(it).epochMs >= olderThanEpochMs }
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.write(temp, kept)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { logger.warn("Could not prune audit history: {}", it.message) }
    }

    /** Rolls the current file aside once it reaches [maxFileSizeBytes]. */
    private fun rotateIfNeeded() {
        if (maxFileSizeBytes <= 0 || Files.notExists(file) || Files.size(file) < maxFileSizeBytes) {
            return
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        var rolled = file.resolveSibling("${file.fileName}.$stamp")
        var suffix = 1
        while (Files.exists(rolled)) {
            rolled = file.resolveSibling("${file.fileName}.$stamp-$suffix")
            suffix++
        }
        runCatching { Files.move(file, rolled) }
            .onFailure { logger.warn("Could not rotate audit log past {} bytes: {}", maxFileSizeBytes, it.message) }
    }

    /**
     * Decodes each line independently so a single torn/corrupt line (most
     * commonly the last one, from a crash mid-write) is skipped instead of
     * failing the whole history.
     */
    private fun decodeLines(lines: List<String>): List<AuditEntry> {
        var skipped = 0
        val decoded = lines.mapNotNull { line ->
            if (line.isBlank()) {
                return@mapNotNull null
            }
            runCatching { json.decodeFromString<AuditEntry>(line) }.getOrElse {
                skipped++
                null
            }
        }
        if (skipped > 0) {
            logger.warn("Skipped {} unreadable audit line(s) while loading history", skipped)
        }
        return decoded
    }

    private companion object {
        /** Default rotation threshold. */
        const val DEFAULT_MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024

        /** Chunk size read per backward seek while scanning for line boundaries. */
        const val READ_CHUNK_BYTES = 8192

        /** UTF-8 byte value of `\n`; always a standalone byte, never part of a multi-byte sequence. */
        const val NEWLINE: Byte = '\n'.code.toByte()

        /**
         * Reads at most the last [limit] lines of [path], scanning backward
         * from the end in bounded chunks instead of loading the whole file.
         *
         * @param path the file to read.
         * @param limit maximum number of trailing lines.
         * @return the trailing lines, oldest first.
         */
        fun tailLines(path: Path, limit: Int): List<String> {
            val length = Files.size(path)
            if (length == 0L || limit <= 0) {
                return emptyList()
            }
            RandomAccessFile(path.toFile(), "r").use { raf ->
                var pointer = length
                var carry = ByteArray(0)
                val lines = ArrayDeque<String>()
                while (pointer > 0 && lines.size < limit) {
                    val readSize = minOf(READ_CHUNK_BYTES.toLong(), pointer).toInt()
                    pointer -= readSize
                    raf.seek(pointer)
                    val chunk = ByteArray(readSize)
                    raf.readFully(chunk)
                    var buffer = chunk + carry
                    var newlineAt = buffer.lastIndexOf(NEWLINE)
                    while (newlineAt >= 0 && lines.size < limit) {
                        val lineBytes = buffer.copyOfRange(newlineAt + 1, buffer.size)
                        if (lineBytes.isNotEmpty()) {
                            lines.addFirst(String(lineBytes, Charsets.UTF_8))
                        }
                        buffer = buffer.copyOfRange(0, newlineAt)
                        newlineAt = buffer.lastIndexOf(NEWLINE)
                    }
                    carry = buffer
                }
                if (carry.isNotEmpty() && lines.size < limit) {
                    lines.addFirst(String(carry, Charsets.UTF_8))
                }
                return lines.toList()
            }
        }
    }
}
