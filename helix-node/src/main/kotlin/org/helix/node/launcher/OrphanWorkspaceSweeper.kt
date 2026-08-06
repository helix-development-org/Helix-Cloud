package org.helix.node.launcher

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Deletes leftover dynamic-service workspaces under `services/temp` that
 * belong to no adopted/live service — closes the gap where a service that
 * crashed before the node could clean up its own workspace (a hard node
 * kill, a power loss) leaves its temp directory behind forever.
 */
object OrphanWorkspaceSweeper {
    private val logger = LoggerFactory.getLogger(OrphanWorkspaceSweeper::class.java)

    /**
     * Removes every subdirectory of [servicesTemp] whose name is not in
     * [liveServiceIds].
     *
     * @param servicesTemp the `services/temp` directory.
     * @param liveServiceIds ids of services adopted or otherwise known live.
     * @return number of orphaned directories removed.
     */
    fun sweep(servicesTemp: Path, liveServiceIds: Set<String>): Int {
        if (Files.notExists(servicesTemp)) {
            return 0
        }
        val orphans = runCatching {
            Files.newDirectoryStream(servicesTemp).use { stream ->
                stream.filter { it.isDirectory() && it.name !in liveServiceIds }
            }
        }.getOrElse {
            logger.warn("Could not scan {} for orphaned workspaces: {}", servicesTemp, it.message)
            return 0
        }
        var removed = 0
        orphans.forEach { orphan ->
            runCatching { orphan.toFile().deleteRecursively() }
                .onSuccess {
                    removed++
                    logger.info("Removed orphaned workspace {}", orphan.name)
                }
                .onFailure { logger.warn("Could not remove orphaned workspace {}: {}", orphan.name, it.message) }
        }
        return removed
    }
}
