package org.helix.node.backup

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Creates, lists, restores and deletes zip backups of static service
 * workspaces under `Helix/backups/<serviceId>/<timestamp>.zip`, plus one
 * reserved pseudo-service (`_addon-data`) holding a snapshot of the
 * `json`-mode data directories (addon documents, tasks, translations,
 * audit) passed in as [dataSources].
 *
 * Backups may be taken while the service runs (best effort); restores are
 * only allowed while the service is stopped. A per-service retention keeps
 * the newest [retention] archives. Usable through actions (and therefore the
 * job scheduler) and the panel routes.
 *
 * In `postgres`/`mongodb` storage mode [dataSources] is empty — those
 * documents live in the shared database, so `pg_dump`/`mongodump` (run
 * against [org.helix.node.config.NodeConfig.StorageSettings]) is the
 * recommended backup path instead of [createData].
 *
 * @property backupsDir root directory holding all backup archives.
 * @property staticServicesDir directory of persistent service workspaces.
 * @property isActive whether the service with the given id is running.
 * @property retention maximum archives kept per service.
 * @property clock epoch-millis source, injectable for tests.
 * @property dataSources `json`-mode data directories to snapshot with
 *  [createData], keyed by the top-level folder name they get inside the
 *  archive; empty when addon documents live in a shared database instead.
 */
class BackupService(
    private val backupsDir: Path,
    private val staticServicesDir: Path,
    private val isActive: (serviceId: String) -> Boolean = { false },
    private val retention: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dataSources: Map<String, Path> = emptyMap(),
) {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)

    /**
     * Zips a static workspace into a new backup archive.
     *
     * @param serviceId the static service id.
     * @return metadata of the created archive.
     * @throws IllegalArgumentException if no static workspace exists.
     */
    @Synchronized
    fun create(serviceId: String): BackupInfo {
        val workspace = staticServicesDir.resolve(serviceId).normalize()
        require(workspace.parent == staticServicesDir.normalize() && workspace.isDirectory()) {
            "no static workspace for $serviceId"
        }
        return zip(serviceId, mapOf("" to workspace))
    }

    /**
     * Zips the configured `json`-mode data directories ([dataSources]) —
     * addon documents, tasks, translations, audit — into a new backup
     * archive, each under its own top-level folder in the zip.
     *
     * @return metadata of the created archive.
     * @throws IllegalStateException if no data sources are configured (for
     *  example in `postgres`/`mongodb` storage mode).
     */
    @Synchronized
    fun createData(): BackupInfo {
        check(dataSources.isNotEmpty()) {
            "no json-mode data directories configured for this storage mode " +
                "(use pg_dump/mongodump for postgres/mongodb instead)"
        }
        return zip(DATA_BACKUP_ID, dataSources)
    }

    private fun zip(id: String, sources: Map<String, Path>): BackupInfo {
        val directory = Files.createDirectories(backupsDir.resolve(id))
        val now = clock()
        val fileName = TIMESTAMP.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())) + ".zip"
        val target = directory.resolve(fileName)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            sources.forEach { (label, root) ->
                if (!root.isDirectory()) {
                    return@forEach
                }
                Files.walk(root).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val entryName = root.relativize(file).toString()
                        zip.putNextEntry(ZipEntry(if (label.isEmpty()) entryName else "$label/$entryName"))
                        runCatching { Files.copy(file, zip) }
                            .onFailure { logger.debug("Skipping unreadable file {}: {}", file, it.message) }
                        zip.closeEntry()
                    }
                }
            }
        }
        prune(id)
        logger.info("Created backup {} for {}", fileName, id)
        return BackupInfo(id, fileName, Files.size(target), now)
    }

    /**
     * Lists backup archives, newest first.
     *
     * @param serviceId restricts to one service; `null` lists everything.
     * @return the archives.
     */
    fun list(serviceId: String? = null): List<BackupInfo> {
        if (!Files.isDirectory(backupsDir)) {
            return emptyList()
        }
        val serviceDirs = if (serviceId != null) {
            listOf(backupsDir.resolve(serviceId)).filter { it.isDirectory() }
        } else {
            backupsDir.listDirectoryEntries().filter { it.isDirectory() }
        }
        return serviceDirs.flatMap { dir ->
            dir.listDirectoryEntries("*.zip").map { file ->
                BackupInfo(
                    serviceId = dir.name,
                    fileName = file.name,
                    sizeBytes = Files.size(file),
                    createdAtEpochMs = Files.getLastModifiedTime(file).toMillis(),
                )
            }
        }.sortedByDescending { it.createdAtEpochMs }
    }

    /**
     * Restores a backup into the service workspace, replacing its content.
     *
     * The archive is fully extracted and validated in a staging directory
     * first; only then is the workspace cleared and the staged content moved
     * in. A corrupt or malicious archive (zip-slip entry, truncated zip)
     * therefore fails BEFORE the existing workspace is touched, instead of
     * leaving it wiped with nothing restored.
     *
     * @param serviceId the static service id.
     * @param fileName the archive to restore.
     * @throws IllegalArgumentException if the archive is unknown, invalid, or
     *  the service is still running.
     */
    @Synchronized
    fun restore(serviceId: String, fileName: String) {
        require(!isActive(serviceId)) { "stop $serviceId before restoring a backup" }
        val archive = resolveArchive(serviceId, fileName)
        require(Files.isRegularFile(archive)) { "unknown backup: $serviceId/$fileName" }
        val workspace = staticServicesDir.resolve(serviceId).normalize()
        stageAndSwap(archive, mapOf("" to workspace)) {
            // Re-check as close to the destructive swap as possible: the service could have
            // been started while the archive was being extracted into the staging directory.
            require(!isActive(serviceId)) { "stop $serviceId before restoring a backup" }
        }
        logger.info("Restored backup {} into {}", fileName, serviceId)
    }

    /**
     * Restores a [createData] backup, replacing the current content of every
     * configured [dataSources] directory. The node should be stopped (or at
     * least addons quiesced) while this runs — unlike static workspace
     * restores this is not gated on an `isActive` check, since addon storage
     * has no single owning service to stop.
     *
     * @param fileName the archive to restore.
     * @throws IllegalStateException if no data sources are configured.
     * @throws IllegalArgumentException if the archive is unknown.
     */
    @Synchronized
    fun restoreData(fileName: String) {
        check(dataSources.isNotEmpty()) {
            "no json-mode data directories configured for this storage mode " +
                "(use pg_dump/mongodump for postgres/mongodb instead)"
        }
        val archive = resolveArchive(DATA_BACKUP_ID, fileName)
        require(Files.isRegularFile(archive)) { "unknown backup: $DATA_BACKUP_ID/$fileName" }
        stageAndSwap(archive, dataSources)
        logger.info("Restored addon-data backup {}", fileName)
    }

    /**
     * Extracts [archive] into a staging directory (validating every entry
     * there), then — and only then — clears each target directory and moves
     * the staged content in. [beforeSwap] runs between successful extraction
     * and the first destructive step, for a last-moment activity re-check.
     */
    private fun stageAndSwap(archive: Path, targets: Map<String, Path>, beforeSwap: () -> Unit = {}) {
        val staging = Files.createTempDirectory(archive.parent, ".restore-")
        try {
            val staged = targets.mapValues { (label, _) ->
                if (label.isEmpty()) staging else staging.resolve(label)
            }
            extract(archive, staged)
            beforeSwap()
            targets.forEach { (label, root) ->
                clearDirectory(root)
                Files.createDirectories(root)
                val source = staged.getValue(label)
                if (Files.isDirectory(source)) {
                    moveContents(source, root)
                }
            }
        } finally {
            clearDirectory(staging)
            Files.deleteIfExists(staging)
        }
    }

    /** Moves everything under [from] into [into], preserving the directory structure. */
    private fun moveContents(from: Path, into: Path) {
        Files.walk(from).use { stream ->
            stream.filter { it != from }.forEach { path ->
                val target = into.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun extract(archive: Path, sources: Map<String, Path>) {
        val singleRoot = sources[""].takeIf { sources.size == 1 }
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val (root, relative) = if (singleRoot != null) {
                    singleRoot to entry.name
                } else {
                    val separator = entry.name.indexOf('/')
                    if (separator < 0) {
                        return@forEach
                    }
                    val label = entry.name.substring(0, separator)
                    val source = sources[label] ?: return@forEach
                    source to entry.name.substring(separator + 1)
                }
                val target = root.resolve(relative).normalize()
                // zip-slip guard: entries must stay inside their source root
                require(target.startsWith(root.normalize())) { "illegal archive entry: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    /**
     * Deletes a backup archive.
     *
     * @param serviceId the service id.
     * @param fileName the archive file name.
     * @return `true` if the archive existed.
     */
    @Synchronized
    fun delete(serviceId: String, fileName: String): Boolean =
        Files.deleteIfExists(resolveArchive(serviceId, fileName))

    /**
     * The static workspaces available for backups, with running state.
     *
     * @return workspaces sorted by id.
     */
    fun workspaces(): List<BackupsOverview.WorkspaceState> {
        if (!Files.isDirectory(staticServicesDir)) {
            return emptyList()
        }
        return staticServicesDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .map { BackupsOverview.WorkspaceState(it.name, isActive(it.name)) }
            .sortedBy { it.serviceId }
    }

    private fun resolveArchive(serviceId: String, fileName: String): Path {
        val archive = backupsDir.resolve(serviceId).resolve(fileName).normalize()
        require(archive.startsWith(backupsDir.normalize()) && fileName.endsWith(".zip")) {
            "invalid backup reference"
        }
        return archive
    }

    private fun prune(serviceId: String) {
        list(serviceId).drop(retention).forEach { old ->
            runCatching { delete(serviceId, old.fileName) }
        }
    }

    private fun clearDirectory(directory: Path) {
        if (!Files.isDirectory(directory)) {
            return
        }
        Files.walk(directory).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .filter { it != directory }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        /** Archive file name pattern (millis avoid same-second collisions). */
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        /** Reserved pseudo-service id [createData]/[restoreData] archives live under. */
        const val DATA_BACKUP_ID = "_addon-data"
    }
}
