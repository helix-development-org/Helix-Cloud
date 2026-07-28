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
            // Only the proxy's port is published to the host: backend Paper containers
            // stay reachable solely over the docker network (by the proxy container),
            // never directly from outside the docker host.
            if (spec.task.environment.proxy) {
                add("-p")
                add("${spec.port}:${spec.port}")
            }
            add("-v")
            // :z relabels the mount for SELinux hosts (Fedora/RHEL) and is
            // a no-op elsewhere; without it the container cannot read the
            // workspace and exits immediately.
            add("${spec.workspace.toAbsolutePath()}:/helix:z")
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
        return DockerServiceHandle(name, runner, spec.workspace)
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
