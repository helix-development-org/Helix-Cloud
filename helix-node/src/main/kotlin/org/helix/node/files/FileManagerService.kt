package org.helix.node.files

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Browses and edits files inside service workspaces and templates for the
 * dashboard file manager.
 *
 * Roots are addressed as `static:<serviceId>`, `temp:<serviceId>` or
 * `template:<name>`. Every path is normalized and confined to its root —
 * traversal attempts (`..`, absolute paths) are rejected.
 *
 * @property staticServicesDir persistent service workspaces.
 * @property tempServicesDir throw-away service workspaces.
 * @property templatesDir template directories.
 * @property maxEditableBytes largest file readable/writable as text.
 */
class FileManagerService(
    private val staticServicesDir: Path,
    private val tempServicesDir: Path,
    private val templatesDir: Path,
    private val maxEditableBytes: Long = 1_048_576,
) {
    /**
     * All available roots, sorted: templates first, then workspaces.
     *
     * @return root ids such as `template:default` or `static:Lobby-1`.
     */
    fun roots(): List<String> = buildList {
        addAll(children(templatesDir).map { "template:${it.name}" })
        addAll(children(staticServicesDir).map { "static:${it.name}" })
        addAll(children(tempServicesDir).map { "temp:${it.name}" })
    }

    /**
     * Lists a directory, directories first.
     *
     * @param root the root id.
     * @param path directory path relative to the root, empty for the root.
     * @return the entries sorted by type and name.
     */
    fun list(root: String, path: String): List<FileEntry> {
        val directory = resolve(root, path)
        require(directory.isDirectory()) { "not a directory: $path" }
        return directory.listDirectoryEntries()
            .map { entry ->
                FileEntry(
                    name = entry.name,
                    directory = entry.isDirectory(),
                    sizeBytes = if (entry.isDirectory()) 0 else Files.size(entry),
                    modifiedAtEpochMs = Files.getLastModifiedTime(entry).toMillis(),
                )
            }
            .sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    /**
     * Reads a text file.
     *
     * @param root the root id.
     * @param path file path relative to the root.
     * @return the file content.
     * @throws IllegalArgumentException for directories or oversized files.
     */
    fun read(root: String, path: String): FileContent {
        val file = resolve(root, path)
        require(Files.isRegularFile(file)) { "not a file: $path" }
        require(Files.size(file) <= maxEditableBytes) { "file too large to edit (max ${maxEditableBytes / 1024} KiB)" }
        return FileContent(Files.readString(file))
    }

    /**
     * Writes a text file, creating parent directories as needed.
     *
     * @param root the root id.
     * @param path file path relative to the root.
     * @param content new file text.
     */
    fun write(root: String, path: String, content: String) {
        require(content.length <= maxEditableBytes) { "content too large (max ${maxEditableBytes / 1024} KiB)" }
        val file = resolve(root, path)
        require(!file.isDirectory()) { "is a directory: $path" }
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    /**
     * Deletes a file or directory (recursively).
     *
     * @param root the root id.
     * @param path path relative to the root; the root itself is protected.
     * @return `true` if something was deleted.
     */
    fun delete(root: String, path: String): Boolean {
        val target = resolve(root, path)
        require(target != rootDirectory(root)) { "cannot delete the root itself" }
        if (!Files.exists(target)) {
            return false
        }
        Files.walk(target).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        return true
    }

    private fun rootDirectory(root: String): Path {
        val separator = root.indexOf(':')
        require(separator > 0) { "invalid root: $root" }
        val kind = root.substring(0, separator)
        val id = root.substring(separator + 1)
        require(id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")) {
            "invalid root id: $id"
        }
        val base = when (kind) {
            "static" -> staticServicesDir
            "temp" -> tempServicesDir
            "template" -> templatesDir
            else -> throw IllegalArgumentException("unknown root kind: $kind")
        }
        val directory = base.resolve(id).normalize()
        require(directory.parent == base.normalize() && directory.isDirectory()) { "unknown root: $root" }
        return directory
    }

    private fun resolve(root: String, path: String): Path {
        val rootDir = rootDirectory(root)
        val resolved = rootDir.resolve(path.trimStart('/')).normalize()
        // traversal guard: the resolved path must stay inside the root
        require(resolved.startsWith(rootDir)) { "illegal path: $path" }
        return resolved
    }

    private fun children(directory: Path): List<Path> =
        if (directory.isDirectory()) {
            directory.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }
        } else {
            emptyList()
        }
}
