package org.helix.node.backup

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
import org.slf4j.LoggerFactory

/**
 * Creates, lists, restores and deletes zip backups of static service
 * workspaces under `Helix/backups/<serviceId>/<timestamp>.zip`.
 *
 * Backups may be taken while the service runs (best effort); restores are
 * only allowed while the service is stopped. A per-service retention keeps
 * the newest [retention] archives. Usable through actions (and therefore the
 * job scheduler) and the panel routes.
 *
 * @property backupsDir root directory holding all backup archives.
 * @property staticServicesDir directory of persistent service workspaces.
 * @property isActive whether the service with the given id is running.
 * @property retention maximum archives kept per service.
 * @property clock epoch-millis source, injectable for tests.
 */
class BackupService(
    private val backupsDir: Path,
    private val staticServicesDir: Path,
    private val isActive: (serviceId: String) -> Boolean = { false },
    private val retention: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
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
        val directory = Files.createDirectories(backupsDir.resolve(serviceId))
        val now = clock()
        val fileName = TIMESTAMP.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())) + ".zip"
        val target = directory.resolve(fileName)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            Files.walk(workspace).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    zip.putNextEntry(ZipEntry(workspace.relativize(file).toString()))
                    runCatching { Files.copy(file, zip) }
                        .onFailure { logger.debug("Skipping unreadable file {}: {}", file, it.message) }
                    zip.closeEntry()
                }
            }
        }
        prune(serviceId)
        logger.info("Created backup {} for {}", fileName, serviceId)
        return BackupInfo(serviceId, fileName, Files.size(target), now)
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
     * @param serviceId the static service id.
     * @param fileName the archive to restore.
     * @throws IllegalArgumentException if the archive is unknown or the
     *  service is still running.
     */
    @Synchronized
    fun restore(serviceId: String, fileName: String) {
        require(!isActive(serviceId)) { "stop $serviceId before restoring a backup" }
        val archive = resolveArchive(serviceId, fileName)
        require(Files.isRegularFile(archive)) { "unknown backup: $serviceId/$fileName" }
        val workspace = staticServicesDir.resolve(serviceId).normalize()
        clearDirectory(workspace)
        Files.createDirectories(workspace)
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = workspace.resolve(entry.name).normalize()
                // zip-slip guard: entries must stay inside the workspace
                require(target.startsWith(workspace)) { "illegal archive entry: ${entry.name}" }
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
        logger.info("Restored backup {} into {}", fileName, serviceId)
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
    }
}
