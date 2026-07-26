package org.helix.node.packs

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.slf4j.LoggerFactory

/**
 * Builds the single network resource pack out of the `pack.zip` files of
 * all enabled addons.
 *
 * The merge is deterministic: addons are processed sorted by id, entries
 * are written sorted by name with a fixed timestamp, and the `pack.mcmeta`
 * is generated (the addons' own `pack.mcmeta` entries are skipped). When
 * two addons ship the same entry path, the first addon (sorted by id) wins
 * and a warning names both. The result is served publicly by the control
 * API under `/api/v1/packs/network.zip` and distributed to players by the
 * Velocity bridge on proxy join.
 *
 * @property directory the `Helix/packs/` output directory.
 */
class NetworkPackService(private val directory: Path) {
    private val logger = LoggerFactory.getLogger(NetworkPackService::class.java)

    @Volatile
    private var current: Path? = null

    @Volatile
    private var currentSha1: String? = null

    /**
     * Merges the given addon packs into `network.zip` and refreshes the
     * cached SHA-1. Without any packs the previous file is removed.
     *
     * @param packs addon id to extracted `pack.zip` path of enabled addons.
     */
    @Synchronized
    fun rebuild(packs: List<Pair<String, Path>>) {
        Files.createDirectories(directory)
        val target = directory.resolve(PACK_FILE)
        if (packs.isEmpty()) {
            Files.deleteIfExists(target)
            current = null
            currentSha1 = null
            return
        }
        val entries = sortedMapOf<String, ByteArray>()
        val owners = mutableMapOf<String, String>()
        packs.sortedBy { it.first }.forEach { (addonId, pack) -> mergePack(addonId, pack, entries, owners) }
        entries[MCMETA] = packMcMeta().encodeToByteArray()
        Files.newOutputStream(target).use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    val entry = ZipEntry(name)
                    entry.time = 0
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        current = target
        currentSha1 = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(target))
            .joinToString("") { "%02x".format(it) }
        logger.info("Rebuilt network pack from {} addon pack(s), sha1 {}", packs.size, currentSha1)
    }

    /**
     * The current merged pack file.
     *
     * @return the `network.zip` path, or `null` when no addon ships a pack.
     */
    fun packFile(): Path? = current?.takeIf { Files.exists(it) }

    /**
     * The hex SHA-1 of the current merged pack, cached at rebuild time.
     *
     * @return the hash, or `null` when no pack exists.
     */
    fun sha1(): String? = currentSha1

    /**
     * Persists (or clears) the operator-configured public download URL
     * override, kept across restarts in `Helix/packs/url.txt`.
     *
     * @param url the client-reachable URL, or `null` to reset to automatic
     *   resolution.
     */
    @Synchronized
    fun setPublicUrl(url: String?) {
        Files.createDirectories(directory)
        val file = directory.resolve(URL_FILE)
        if (url == null) {
            Files.deleteIfExists(file)
        } else {
            Files.writeString(file, url)
        }
    }

    /**
     * The persisted public download URL override.
     *
     * @return the configured URL, or `null` for automatic resolution.
     */
    @Synchronized
    fun publicUrl(): String? {
        val file = directory.resolve(URL_FILE)
        if (!Files.exists(file)) {
            return null
        }
        return Files.readString(file).trim().takeIf { it.isNotBlank() }
    }

    private fun mergePack(
        addonId: String,
        pack: Path,
        entries: MutableMap<String, ByteArray>,
        owners: MutableMap<String, String>,
    ) {
        ZipFile(pack.toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name != MCMETA }
                .forEach { entry ->
                    val owner = owners[entry.name]
                    if (owner != null) {
                        logger.warn(
                            "Resource pack conflict on {}: addons {} and {} both provide it — keeping {}",
                            entry.name,
                            owner,
                            addonId,
                            owner,
                        )
                    } else {
                        owners[entry.name] = addonId
                        entries[entry.name] = zip.getInputStream(entry).readAllBytes()
                    }
                }
        }
    }

    private fun packMcMeta(): String =
        """{"pack":{"pack_format":$PACK_FORMAT,"supported_formats":[$MIN_FORMAT,$MAX_FORMAT],""" +
            """"description":"Helix network pack"}}"""

    private companion object {
        /** File name of the merged network pack. */
        const val PACK_FILE = "network.zip"

        /** File persisting the operator-configured public download URL. */
        const val URL_FILE = "url.txt"

        /** Pack metadata entry name, generated instead of merged. */
        const val MCMETA = "pack.mcmeta"

        /** Declared `pack_format` of the generated `pack.mcmeta`. */
        const val PACK_FORMAT = 46

        /** Lower bound of the declared `supported_formats` range. */
        const val MIN_FORMAT = 34

        /** Upper bound of the declared `supported_formats` range. */
        const val MAX_FORMAT = 999
    }
}
