package org.helix.node.launcher

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Spawns a fresh `Launcher.jar` process, used by the backend and launcher
 * restart actions.
 *
 * The new process inherits stdin/stdout of the current one (so an
 * interactive console stays usable) and runs in the same working
 * directory, picking up the same `Helix/` data directory. The environment
 * variable `HELIX_RELAUNCH=1` tells the successor it was respawned, so it
 * stays alive even when stdin ends immediately.
 */
object LauncherRespawn {
    private val logger = LoggerFactory.getLogger(LauncherRespawn::class.java)

    /**
     * Resolves the running `Launcher.jar`.
     *
     * @return the jar path, or `null` when not running from a jar (for
     *   example out of Gradle's class directories).
     */
    fun launcherJar(): Path? = runCatching {
        Path.of(LauncherRespawn::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull()?.takeIf { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }

    /**
     * Starts a detached successor launcher process.
     *
     * stdout/stderr stay on the current console, but stdin is detached
     * (`/dev/null`): the successor must never read the inherited terminal —
     * once this process exits, its orphaned process group would only get
     * `EIO` from it. The successor parks on the immediate EOF instead
     * (`HELIX_RELAUNCH=1`) and is controlled via panel, REST or in-game.
     *
     * @param jar the launcher jar to execute; a freshly replaced jar file
     *   starts the new version.
     * @return `true` when the process was spawned.
     */
    fun spawn(jar: Path): Boolean = runCatching {
        val java = ProcessHandle.current().info().command().orElse("java")
        val devNull = Path.of("/dev/null")
        ProcessBuilder(java, "-jar", jar.toAbsolutePath().toString())
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply {
                if (Files.exists(devNull)) {
                    redirectInput(devNull.toFile())
                }
                environment()["HELIX_RELAUNCH"] = "1"
            }
            .start()
        true
    }.onFailure { logger.error("Failed to spawn {}", jar, it) }.getOrDefault(false)
}
