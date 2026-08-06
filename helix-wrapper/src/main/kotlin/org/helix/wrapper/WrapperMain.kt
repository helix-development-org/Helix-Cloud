package org.helix.wrapper

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point of the universal service wrapper.
 *
 * The wrapper is extracted from `Launcher.jar` into every service workspace.
 * It reads `wrapper.properties`, starts the configured server jar and exits
 * with the server's exit code — identically as node child process and as
 * docker container entrypoint.
 */
object WrapperMain {
    /**
     * Boots the wrapper inside a service workspace.
     *
     * @param args command line arguments; currently unused.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val config = WrapperConfig.load(Path.of("wrapper.properties"))
        println("[helix-wrapper] starting service ${config.serviceId} (${config.serverJar})")
        // Reuse the JVM binary the wrapper itself runs on — like the node does
        // when it spawns the wrapper. A bare "java" would require a JVM on the
        // PATH, which an install via install.sh (private JDK under /opt/helix/jdk)
        // does not have.
        val javaExecutable = ProcessHandle.current().info().command().orElse("java")
        val exitCode = ServerProcessRunner().run(config.command(javaExecutable), Path.of("console.in"))
        println("[helix-wrapper] service ${config.serviceId} exited with code $exitCode")
        exitProcess(exitCode)
    }
}
