package org.helix.node.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import org.helix.api.storage.AddonStorage

/**
 * [AddonStorage] that keeps one `<key>.json` file per document in the
 * addon's data directory — the default `json` storage mode.
 *
 * @property directory the addon's data directory.
 */
class JsonFileAddonStorage(private val directory: Path) : AddonStorage {
    override fun read(key: String): String? {
        val file = directory.resolve("$key.json")
        return if (Files.exists(file)) Files.readString(file) else null
    }

    override fun write(key: String, value: String) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("$key.json"), value)
    }

    override fun delete(key: String): Boolean = Files.deleteIfExists(directory.resolve("$key.json"))

    override fun keys(): List<String> {
        if (Files.notExists(directory)) {
            return emptyList()
        }
        return directory.listDirectoryEntries()
            .filter { it.extension == "json" }
            .map { it.nameWithoutExtension }
    }
}
