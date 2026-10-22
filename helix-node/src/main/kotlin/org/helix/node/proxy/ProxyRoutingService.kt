package org.helix.node.proxy

import org.helix.api.execution.ExecutorType
import org.helix.api.proxy.RoutingBackend
import org.helix.api.proxy.RoutingSnapshot
import org.helix.api.service.ServiceState
import org.helix.node.services.ManagedService
import org.helix.node.services.ServiceManager
import org.helix.node.services.docker.DockerNames
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Computes the routing view proxies poll from the node.
 *
 * Backend addresses depend on the executors of proxy and backend:
 *
 * | proxy   | backend | address                       |
 * |---------|---------|-------------------------------|
 * | docker  | docker  | `containerName:port`          |
 * | docker  | process | `host.docker.internal:port`   |
 * | process | docker  | `127.0.0.1:hostPort`          |
 * | process | process | `127.0.0.1:port`              |
 *
 * The network-wide maintenance flag is persisted to [stateFile] (when
 * given) so a closed test phase survives cold starts of the node, not just
 * the self-restart handled by the launcher's restart state.
 *
 * @property manager source of live service state.
 * @property stateFile optional `maintenance.json` the flag is persisted to.
 */
class ProxyRoutingService(
    private val manager: ServiceManager,
    private val stateFile: Path? = null,
) {
    private val logger = LoggerFactory.getLogger(ProxyRoutingService::class.java)

    @Volatile
    private var maintenanceFlag: Boolean = loadMaintenance()

    /** Whether the network rejects regular joins; changes are persisted. */
    var maintenance: Boolean
        get() = maintenanceFlag
        set(value) {
            maintenanceFlag = value
            persistMaintenance(value)
        }

    /**
     * Builds the snapshot for one proxy service.
     *
     * Only `RUNNING` backend services are routed.
     *
     * @param proxyServiceId id of the polling proxy service.
     * @return backends with resolved addresses and the maintenance flag.
     */
    fun snapshot(proxyServiceId: String): RoutingSnapshot {
        val proxyExecutor = manager.find(proxyServiceId)?.task?.executor ?: ExecutorType.PROCESS
        val backends = manager.managedServices()
            .filter { !it.task.environment.proxy && it.state == ServiceState.RUNNING }
            .map { backend -> toRoutingBackend(proxyExecutor, backend) }
        return RoutingSnapshot(backends = backends, maintenance = maintenance)
    }

    private fun toRoutingBackend(proxyExecutor: ExecutorType, backend: ManagedService): RoutingBackend {
        val host = when (proxyExecutor to backend.task.executor) {
            ExecutorType.DOCKER to ExecutorType.DOCKER -> DockerNames.containerName(backend.id)
            ExecutorType.DOCKER to ExecutorType.PROCESS -> "host.docker.internal"
            else -> "127.0.0.1"
        }
        return RoutingBackend(
            serviceId = backend.id,
            taskName = backend.task.name,
            host = host,
            port = backend.port,
            fallbackEligible = backend.task.fallbackEligible,
            maintenance = backend.task.maintenance,
        )
    }

    private fun loadMaintenance(): Boolean {
        val file = stateFile ?: return false
        if (!Files.exists(file)) {
            return false
        }
        return runCatching { MAINTENANCE_PATTERN.find(Files.readString(file))?.groupValues?.get(1) == "true" }
            .onFailure { logger.warn("Could not read {}: {}", file, it.message) }
            .getOrDefault(false)
    }

    @Synchronized
    private fun persistMaintenance(value: Boolean) {
        val file = stateFile ?: return
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, "{\n  \"maintenance\": $value\n}\n")
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { logger.warn("Could not persist maintenance state to {}: {}", file, it.message) }
    }

    private companion object {
        val MAINTENANCE_PATTERN = Regex("\"maintenance\"\\s*:\\s*(true|false)")
    }
}
