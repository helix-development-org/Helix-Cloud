package org.helix.node.services

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.jvm.optionals.getOrNull

/**
 * Runs services as local child processes of the node.
 *
 * The wrapper is started with `java -jar Wrapper.jar` inside the workspace;
 * combined output is redirected to `service.log`.
 *
 * @property javaExecutable java binary; defaults to the node's own JVM.
 */
class ProcessServiceExecutor(
    private val javaExecutable: String =
        ProcessHandle.current().info().command().getOrNull() ?: "java",
) : ServiceExecutor {
    /**
     * Starts the wrapper process for the prepared workspace.
     *
     * @param spec prepared workspace and start parameters.
     * @return handle over the wrapper process.
     */
    override fun start(spec: ServiceStartSpec): ServiceHandle {
        val logFile = spec.workspace.resolve("service.log")
        val builder = ProcessBuilder(javaExecutable, "-jar", "Wrapper.jar")
            .directory(spec.workspace.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        builder.environment().putAll(spec.environmentVariables)
        val process = builder.start()
        return ProcessServiceHandle(process, logFile)
    }
}

/**
 * [ServiceHandle] over a local wrapper process.
 *
 * @property process the wrapper process.
 * @property logFile combined output of wrapper and server.
 */
class ProcessServiceHandle(
    private val process: Process,
    private val logFile: Path,
) : ServiceHandle {
    /** Whether the wrapper process is still running. */
    override val alive: Boolean
        get() = process.isAlive

    /**
     * Requests a graceful stop by terminating the wrapper, which forwards
     * the termination to the server.
     */
    override fun stop() {
        process.destroy()
    }

    /**
     * Kills the wrapper process immediately.
     */
    override fun kill() {
        process.destroyForcibly()
    }

    /**
     * Registers a callback invoked once when the process exits.
     *
     * @param callback receives the exit code.
     */
    override fun onExit(callback: (Int) -> Unit) {
        process.onExit().thenAccept { callback(it.exitValue()) }
    }

    /**
     * Reads the newest lines of `service.log`.
     *
     * @param tail maximum number of lines from the end.
     * @return the log lines, oldest first.
     */
    override fun logs(tail: Int): List<String> = try {
        Files.readAllLines(logFile).takeLast(tail)
    } catch (_: IOException) {
        emptyList()
    }
}
