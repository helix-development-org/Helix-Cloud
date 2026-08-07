package org.helix.node.launcher

import org.helix.node.logging.LogCapture
import java.nio.file.Path

/**
 * Entry point of the single `Launcher.jar` artifact.
 *
 * The launcher prepares the `Helix/` data directory on first start, boots
 * the node and hands control to the interactive CLI.
 */
object LauncherMain {
    /**
     * Boots Helix-Cloud from the current working directory.
     *
     * @param args command line arguments; currently unused.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val logBuffer = LogCapture.install()
        val dataDirectory = HelixDirectoryInitializer(Path.of("Helix")).initialize()
        val node = HelixNode(dataDirectory, logBuffer)
        node.start()
        node.runCli()
    }
}
