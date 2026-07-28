package org.helix.node.proxy

import org.helix.api.execution.ExecutorType
import org.helix.api.proxy.RoutingBackend
import org.helix.api.proxy.RoutingSnapshot
import org.helix.api.service.ServiceState
import org.helix.node.services.ManagedService
import org.helix.node.services.ServiceManager
import org.helix.node.services.docker.DockerNames

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
 * @property manager source of live service state.
 */
class ProxyRoutingService(private val manager: ServiceManager) {
    /** Whether the network rejects regular joins. */
    @Volatile
    var maintenance: Boolean = false

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
}
