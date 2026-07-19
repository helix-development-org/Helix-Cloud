package org.helix.node.launcher

import java.nio.file.Path

/**
 * Entry point of the single `Launcher.jar` artifact.
 *
 * The launcher prepares the `Helix/` data directory on first start and then
 * boots the node from it.
 */
object LauncherMain {
    /**
     * Boots Helix-Cloud from the current working directory.
     *
     * @param args command line arguments; currently unused.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val dataDirectory = HelixDirectoryInitializer(Path.of("Helix")).initialize()
        println("Helix-Cloud ${LauncherMain::class.java.`package`.implementationVersion ?: "dev"}")
        println("Data directory ready: ${dataDirectory.toAbsolutePath()}")
    }
}
