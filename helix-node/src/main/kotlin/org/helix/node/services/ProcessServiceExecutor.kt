package org.helix.node.services

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
