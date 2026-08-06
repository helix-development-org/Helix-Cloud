package org.helix.wrapper

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Configuration of a single wrapped service, read from
 * `wrapper.properties` in the service workspace.
 *
 * The node writes this file while preparing the workspace; the format is
 * plain java properties so the wrapper needs no parser dependency.
 *
 * @property serviceId id of the service this wrapper runs.
 * @property serverJar server jar file name relative to the workspace.
 * @property memoryMb maximum JVM heap in megabytes.
 * @property jvmArgs additional JVM arguments.
 * @property serverArgs arguments passed to the server jar.
 */
data class WrapperConfig(
    val serviceId: String,
    val serverJar: String,
    val memoryMb: Int,
    val jvmArgs: List<String> = emptyList(),
    val serverArgs: List<String> = emptyList(),
) {
    /**
     * Builds the full server start command.
     *
     * @param javaExecutable java binary to use.
     * @return command list for a [ProcessBuilder].
     */
    fun command(javaExecutable: String = "java"): List<String> = buildList {
        add(javaExecutable)
        add("-Xms${memoryMb}M")
        add("-Xmx${memoryMb}M")
        addAll(jvmArgs)
        add("-jar")
        add(serverJar)
        addAll(serverArgs)
    }

    companion object {
        /**
         * Loads the configuration from a properties file.
         *
         * @param file path of `wrapper.properties`.
         * @return the parsed configuration.
         * @throws IllegalArgumentException if required keys are missing.
         */
        fun load(file: Path): WrapperConfig {
            val properties = Properties()
            Files.newBufferedReader(file).use(properties::load)

            /** Reads a mandatory property or fails with a clear message. */
            fun required(key: String): String =
                requireNotNull(properties.getProperty(key)) { "wrapper.properties misses key: $key" }
            return WrapperConfig(
                serviceId = required("serviceId"),
                serverJar = required("serverJar"),
                memoryMb = required("memoryMb").toInt(),
                jvmArgs = properties.getProperty("jvmArgs", "").split(" ").filter { it.isNotBlank() },
                serverArgs = properties.getProperty("serverArgs", "").split(" ").filter { it.isNotBlank() },
            )
        }
    }
}
