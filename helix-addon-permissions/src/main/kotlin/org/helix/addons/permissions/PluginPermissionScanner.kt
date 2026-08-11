package org.helix.addons.permissions

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Scans service directories for backend plugins (jars in a `plugins`
 * subdirectory) and extracts the permission nodes each plugin declares in its
 * `plugin.yml` (`permissions:` section keys and per-command `permission:`
 * values). The results feed the network-wide permission catalog.
 *
 * @property roots directories whose workspaces are scanned, as provided by
 *  the addon context (service workspaces and templates).
 */
class PluginPermissionScanner(private val roots: () -> List<Path>) {
    /**
     * Scans all roots and returns plugin name → declared permission nodes.
     *
     * @return permissions grouped by plugin name, sorted by plugin.
     */
    fun scan(): Map<String, List<String>> {
        val found = sortedMapOf<String, MutableSet<String>>()
        pluginJars().forEach { jar ->
            runCatching { readPluginYml(jar) }.getOrNull()
                ?.let { (name, nodes) -> found.getOrPut(name) { linkedSetOf() }.addAll(nodes) }
        }
        return found.mapValues { (_, nodes) -> nodes.sorted() }
    }

    private fun pluginJars(): List<Path> = roots()
        .filter { Files.isDirectory(it) }
        .flatMap { root -> root.listDirectoryEntries().filter { it.isDirectory() } + root }
        .map { it.resolve("plugins") }
        .filter { Files.isDirectory(it) }
        .flatMap { plugins -> plugins.listDirectoryEntries().filter { it.extension == "jar" } }
        .distinct()

    private fun readPluginYml(jar: Path): Pair<String, List<String>>? =
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry("plugin.yml") ?: zip.getEntry("paper-plugin.yml") ?: return null
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val root = zip.getInputStream(entry).use { yaml.load<Any?>(it) } as? Map<*, *> ?: return null
            val name = root["name"]?.toString() ?: jar.fileName.toString()
            val declared = (root["permissions"] as? Map<*, *>)?.keys?.map { it.toString() }.orEmpty()
            val commandNodes = (root["commands"] as? Map<*, *>)?.values
                ?.mapNotNull { (it as? Map<*, *>)?.get("permission")?.toString() }
                .orEmpty()
            name to (declared + commandNodes).distinct()
        }
}
