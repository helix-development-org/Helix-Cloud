package org.helix.node.services.docker

import java.util.concurrent.atomic.AtomicBoolean
import org.helix.node.config.NodeConfig
import org.helix.node.services.ServiceExecutor
import org.helix.node.services.ServiceHandle
import org.helix.node.services.ServiceStartSpec
import org.slf4j.LoggerFactory

/**
 * Runs services as containers in the Helix docker network.
 *
 * The service workspace is mounted into the container and the wrapper is
 * the container command, so process and docker execution behave
 * identically from the service's point of view.
 *
 * @property settings docker network and image configuration.
 * @property runner CLI runner, injectable for tests.
 */
class DockerServiceExecutor(
    private val settings: NodeConfig.DockerSettings,
    private val runner: CommandRunner = SystemCommandRunner(),
) : ServiceExecutor {
    private val logger = LoggerFactory.getLogger(DockerServiceExecutor::class.java)
    private val networkEnsured = AtomicBoolean(false)

    /**
     * Starts the service container.
     *
     * @param spec prepared workspace and start parameters.
     * @return handle over the container.
     * @throws IllegalStateException if `docker run` fails.
     */
    override fun start(spec: ServiceStartSpec): ServiceHandle {
        ensureNetwork()
        val name = DockerNames.containerName(spec.serviceId)
        runner.run(listOf("docker", "rm", "-f", name))
        val command = buildList {
            add("docker")
            add("run")
            add("-d")
            add("--name")
            add(name)
            add("--network")
            add(settings.network)
            add("--add-host")
            add("host.docker.internal:host-gateway")
            add("-p")
            add("${spec.port}:${spec.port}")
            add("-v")
            add("${spec.workspace.toAbsolutePath()}:/helix")
            add("-w")
            add("/helix")
            spec.environmentVariables.forEach { (key, value) ->
                add("-e")
                add("$key=$value")
            }
            add("--memory")
            add("${spec.task.memoryMb + MEMORY_OVERHEAD_MB}m")
            add(settings.image)
            add("java")
            add("-jar")
            add("Wrapper.jar")
        }
        val result = runner.run(command)
        check(result.success()) {
            "docker run for ${spec.serviceId} failed (${result.exitCode}): ${result.output.trim()}"
        }
        logger.info("Started container {} for {}", name, spec.serviceId)
        return DockerServiceHandle(name, runner)
    }

    private fun ensureNetwork() {
        if (!networkEnsured.compareAndSet(false, true)) {
            return
        }
        val inspect = runner.run(listOf("docker", "network", "inspect", settings.network))
        if (!inspect.success()) {
            val create = runner.run(listOf("docker", "network", "create", settings.network))
            check(create.success()) {
                "creating docker network ${settings.network} failed: ${create.output.trim()}"
            }
            logger.info("Created docker network {}", settings.network)
        }
    }

    private companion object {
        /** Extra container memory on top of the JVM heap, in megabytes. */
        const val MEMORY_OVERHEAD_MB = 256
    }
}

/**
 * [ServiceHandle] over a service container.
 *
 * Exit detection runs a blocking `docker wait` on a daemon thread; the
 * container is removed after it terminated.
 *
 * @property containerName name of the container.
 * @property runner CLI runner.
 */
class DockerServiceHandle(
    val containerName: String,
    private val runner: CommandRunner,
) : ServiceHandle {
    /** Whether the container is running according to `docker inspect`. */
    override val alive: Boolean
        get() = runner.run(listOf("docker", "inspect", "-f", "{{.State.Running}}", containerName))
            .let { it.success() && it.output.trim() == "true" }

    /**
     * Stops the container gracefully with a 30 second timeout.
     */
    override fun stop() {
        runner.run(listOf("docker", "stop", "-t", "30", containerName))
    }

    /**
     * Kills the container immediately.
     */
    override fun kill() {
        runner.run(listOf("docker", "kill", containerName))
    }

    /**
     * Waits for container exit on a daemon thread.
     *
     * @param callback receives the container exit code.
     */
    override fun onExit(callback: (Int) -> Unit) {
        val thread = Thread {
            val result = runner.run(listOf("docker", "wait", containerName))
            val exitCode = result.output.trim().toIntOrNull() ?: -1
            runner.run(listOf("docker", "rm", "-f", containerName))
            callback(exitCode)
        }
        thread.name = "docker-wait-$containerName"
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Reads the newest container log lines.
     *
     * @param tail maximum number of lines from the end.
     * @return the log lines, oldest first.
     */
    override fun logs(tail: Int): List<String> =
        runner.run(listOf("docker", "logs", "--tail", tail.toString(), containerName))
            .output.lines().filter { it.isNotEmpty() }
}
