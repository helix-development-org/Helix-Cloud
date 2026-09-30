package org.helix.node.services

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/**
 * Node-wide cache of what Paper's launcher (Paperclip) downloads on the first start of a workspace: the
 * Mojang server jar (`cache/mojang_<version>.jar`) and the server libraries (`libraries/`). Without it every
 * fresh workspace – each start of a dynamic service – downloads them again (~140 MB).
 *
 * [seed] copies the cached files into a workspace before it starts; [harvest] takes them from a workspace
 * whose Paperclip run completed (the patched jar exists in `versions/<version>`, so every download passed its
 * hash check) into the cache. Files are copied, not linked: a later Paperclip run may rewrite a file in place,
 * which must never corrupt the cache. Writes go through a temp file and an atomic move, so a half-copied
 * file is never visible. Paperclip still verifies every file, a stale cache entry only costs a download.
 *
 * @property root cache root; one subdirectory per server version.
 */
class PaperclipCache(private val root: Path) {
    private val logger = LoggerFactory.getLogger(PaperclipCache::class.java)

    /**
     * Copies the cached Paperclip downloads of [version] into [workspace] where missing or different.
     *
     * @param workspace the service workspace.
     * @param version the Paper version of the task.
     * @return number of files copied.
     */
    fun seed(workspace: Path, version: String): Int = mirror(root.resolve(version), workspace).also {
        if (it > 0) logger.info("Seeded {} cached Paperclip files ({}) into {}", it, version, workspace.fileName)
    }

    /**
     * Stores the Paperclip downloads of [workspace] in the cache, if its Paperclip run completed.
     *
     * @param workspace the service workspace.
     * @param version the Paper version of the task.
     * @return number of files stored.
     */
    fun harvest(workspace: Path, version: String): Int {
        if (!completed(workspace, version)) return 0
        return mirror(workspace, root.resolve(version)).also {
            if (it > 0) logger.info("Cached {} Paperclip files ({}) from {}", it, version, workspace.fileName)
        }
    }

    /** Paperclip is done once the patched server jar exists in `versions/<version>`. */
    private fun completed(workspace: Path, version: String): Boolean {
        val versions = workspace.resolve("versions").resolve(version)
        if (!Files.isDirectory(versions)) return false
        return Files.list(versions).use { files -> files.anyMatch { it.isRegularFile() && it.toString().endsWith(".jar") } }
    }

    private fun mirror(from: Path, to: Path): Int {
        var copied = 0
        DIRECTORIES.forEach { dir ->
            val source = from.resolve(dir)
            if (!Files.isDirectory(source)) return@forEach
            Files.walk(source).use { stream ->
                stream.filter { it.isRegularFile() && it.toString().endsWith(".jar") }.forEach { file ->
                    val target = to.resolve(dir).resolve(source.relativize(file).toString())
                    if (Files.exists(target) && Files.size(target) == Files.size(file)) return@forEach
                    runCatching {
                        Files.createDirectories(target.parent)
                        val temp = target.resolveSibling(".${target.fileName}.part")
                        Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING)
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        copied++
                    }.onFailure { logger.warn("Could not copy Paperclip file {}: {}", file, it.message) }
                }
            }
        }
        return copied
    }

    private companion object {
        /** Workspace directories Paperclip downloads into. */
        val DIRECTORIES = listOf("cache", "libraries")
    }
}
