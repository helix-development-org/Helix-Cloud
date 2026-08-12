package org.helix.node.packs

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.streams.asSequence

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

    @Volatile
    private var generation: Int = readInt(directory.resolve(GENERATION_FILE), 0)

    /**
     * Contributes (or overwrites) a loose file to the runtime pack area at
     * the given in-pack [path], for example
     * `assets/helix_phone/textures/font/appicon_calc.png`. The file is
     * merged into `network.zip` on the next [rebuild]. Paths that escape the
     * runtime area are rejected.
     *
     * @param path the in-pack asset path.
     * @param bytes the file content.
     */
    @Synchronized
    fun contributeAsset(path: String, bytes: ByteArray) {
        val target = resolveDynamic(path) ?: return
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    /**
     * Removes a previously contributed runtime pack asset.
     *
     * @param path the in-pack asset path to remove.
     */
    @Synchronized
    fun removeAsset(path: String) {
        val target = resolveDynamic(path) ?: return
        Files.deleteIfExists(target)
    }

    /**
     * The current pack generation — advances by one whenever a [rebuild]
     * changes the pack content. Persisted across restarts.
     *
     * @return the generation, or `0` before the first non-empty build.
     */
    fun generation(): Int = generation

    /**
     * Merges the given addon packs plus all runtime contributions into
     * `network.zip` and refreshes the cached SHA-1. When the resulting
     * content differs from the last build, [generation] advances by one.
     * Without any packs or contributions the previous file is removed.
     *
     * @param packs addon id to extracted `pack.zip` path of enabled addons.
     */
    @Synchronized
    fun rebuild(packs: List<Pair<String, Path>>) {
        Files.createDirectories(directory)
        val target = directory.resolve(PACK_FILE)
        val entries = sortedMapOf<String, ByteArray>()
        val owners = mutableMapOf<String, String>()
        packs.sortedBy { it.first }.forEach { (addonId, pack) -> mergePack(addonId, pack, entries, owners) }
        // Runtime contributions are authored after build time and win over addon packs.
        mergeDynamic(entries)
        if (entries.isEmpty()) {
            Files.deleteIfExists(target)
            current = null
            currentSha1 = null
            bumpGenerationIfChanged(null)
            return
        }
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
        val sha = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(target))
            .joinToString("") { "%02x".format(it) }
        currentSha1 = sha
        bumpGenerationIfChanged(sha)
        logger.info("Rebuilt network pack ({} entries), sha1 {}, generation {}", entries.size, sha, generation)
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

    /**
     * Merges the runtime contribution directory into [entries]. Later
     * (runtime) files overwrite earlier addon entries at the same path.
     */
    private fun mergeDynamic(entries: MutableMap<String, ByteArray>) {
        val dynamic = directory.resolve(DYNAMIC_DIR)
        if (!Files.isDirectory(dynamic)) {
            return
        }
        Files.walk(dynamic).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    val name = dynamic.relativize(file).toString().replace('\\', '/')
                    if (name != MCMETA) {
                        entries[name] = Files.readAllBytes(file)
                    }
                }
        }
    }

    /**
     * Resolves an in-pack path to a file under the runtime area, rejecting
     * absolute paths and any that escape the directory.
     */
    private fun resolveDynamic(path: String): Path? {
        val normalized = path.trim().trimStart('/')
        val base = directory.resolve(DYNAMIC_DIR).normalize()
        val resolved = base.resolve(normalized).normalize()
        if (!resolved.startsWith(base)) {
            logger.warn("Rejected pack asset path escaping the runtime area: {}", path)
            return null
        }
        return resolved
    }

    /**
     * Advances and persists the generation when the built SHA-1 differs from
     * the last persisted one.
     */
    private fun bumpGenerationIfChanged(sha: String?) {
        val shaFile = directory.resolve(SHA_FILE)
        val previous = if (Files.exists(shaFile)) shaFile.readText().trim() else ""
        val next = sha ?: ""
        if (next == previous) {
            return
        }
        generation += 1
        Files.writeString(directory.resolve(GENERATION_FILE), generation.toString())
        Files.writeString(shaFile, next)
    }

    private fun readInt(file: Path, fallback: Int): Int =
        runCatching { file.readText().trim().toInt() }.getOrDefault(fallback)

    private fun packMcMeta(): String =
        """{"pack":{"pack_format":$PACK_FORMAT,"supported_formats":[$MIN_FORMAT,$MAX_FORMAT],""" +
            """"description":"Helix network pack"}}"""

    private companion object {
        /** File name of the merged network pack. */
        const val PACK_FILE = "network.zip"

        /** Directory holding runtime-contributed loose pack files. */
        const val DYNAMIC_DIR = "dynamic"

        /** File persisting the monotonic pack generation. */
        const val GENERATION_FILE = "generation.txt"

        /** File persisting the last built pack SHA-1 (for generation bumps). */
        const val SHA_FILE = "last-sha1.txt"

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
