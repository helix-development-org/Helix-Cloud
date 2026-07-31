package org.helix.node.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import org.helix.api.storage.AddonStorage
import org.slf4j.LoggerFactory

/**
 * [AddonStorage] that keeps one `<key>.json` file per document in the
 * addon's data directory — the default `json` storage mode.
 *
 * Writes are atomic: the new content lands in a uniquely named sibling
 * `.tmp` file, is fsynced, and only then replaces the real file — with the
 * previous version rotated into a sibling `.bak` file right before that move
 * — so a crash mid-write can never leave a half-written document, and a
 * document found corrupt on read (unreadable as text, e.g. truncated
 * mid-character) falls back to that one prior generation instead of
 * resetting to defaults. Mutations are serialized per storage instance, so
 * two threads writing the same key cannot interleave the backup rotation or
 * clobber each other's temp file.
 *
 * @property directory the addon's data directory.
 */
class JsonFileAddonStorage(private val directory: Path) : AddonStorage {
    private val logger = LoggerFactory.getLogger(JsonFileAddonStorage::class.java)
    private val writeLock = Any()

    override fun read(key: String): String? {
        val safeKey = validateKey(key)
        val file = directory.resolve("$safeKey.json")
        if (Files.notExists(file)) {
            return null
        }
        return try {
            Files.readString(file)
        } catch (failure: IOException) {
            logger.warn("Document {} is corrupt ({}), falling back to its backup generation", file, failure.message)
            val backup = directory.resolve("$safeKey.json.bak")
            if (Files.notExists(backup)) {
                throw IllegalStateException(
                    "Document $safeKey is corrupt and no backup generation is available",
                    failure,
                )
            }
            Files.readString(backup)
        }
    }

    override fun write(key: String, value: String) {
        val safeKey = validateKey(key)
        synchronized(writeLock) {
            Files.createDirectories(directory)
            val target = directory.resolve("$safeKey.json")
            val backup = directory.resolve("$safeKey.json.bak")
            // Unique temp name per write: even a writer outside this lock (a second storage
            // instance over the same directory) can then never truncate a temp file another
            // write is about to move into place.
            val temp = Files.createTempFile(directory, ".$safeKey.", ".tmp")
            try {
                FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    channel.write(ByteBuffer.wrap(value.toByteArray(StandardCharsets.UTF_8)))
                    channel.force(true)
                }
                if (Files.exists(target)) {
                    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: Throwable) {
                runCatching { Files.deleteIfExists(temp) }
                throw failure
            }
        }
    }

    override fun delete(key: String): Boolean {
        val safeKey = validateKey(key)
        synchronized(writeLock) {
            Files.deleteIfExists(directory.resolve("$safeKey.json.bak"))
            return Files.deleteIfExists(directory.resolve("$safeKey.json"))
        }
    }

    override fun keys(): List<String> {
        if (Files.notExists(directory)) {
            return emptyList()
        }
        return directory.listDirectoryEntries()
            .filter { it.extension == "json" }
            .map { it.nameWithoutExtension }
    }

    private companion object {
        /**
         * Rejects storage keys that could escape the addon's data directory
         * (a `/` or `\` segment) or target a hidden/relative entry (a
         * leading `.`) before a path is ever resolved from one — closing
         * the path-traversal-via-key risk when keys are built from external
         * input (player names, incident ids, ...).
         */
        fun validateKey(key: String): String {
            require(!key.contains('/') && !key.contains('\\') && !key.startsWith('.')) {
                "invalid storage key: $key"
            }
            return key
        }
    }
}
