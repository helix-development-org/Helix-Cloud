package org.helix.node.platform

import org.helix.api.platform.PlatformOverview
import org.helix.api.service.ServiceState
import org.helix.node.services.ServiceManager
import org.helix.node.tasks.TaskStore

/**
 * Aggregates node state for dashboards and the CLI.
 *
 * @property version Helix-Cloud version string.
 * @property taskStore configured tasks.
 * @property manager live services.
 */
class PlatformOverviewService(
    private val version: String,
    private val taskStore: TaskStore,
    private val manager: ServiceManager,
) {
    /**
     * Builds the current overview.
     *
     * @return aggregated counters.
     */
    fun overview(): PlatformOverview {
        val services = manager.services()
        val running = services.filter { it.state == ServiceState.RUNNING }
        // Every network player is connected to exactly one proxy AND one backend —
        // summing both layers counts each player twice. With a proxy layer running,
        // its numbers alone are the network total; without one (standalone setup),
        // the backend sum is.
        val proxies = running.filter { it.environment.proxy }
        val countable = proxies.ifEmpty { running }
        return PlatformOverview(
            version = version,
            taskCount = taskStore.all().size,
            servicesRunning = running.size,
            servicesTotal = services.size,
            onlinePlayers = countable.sumOf { it.onlinePlayers },
            maxPlayers = running.filter { !it.environment.proxy }.sumOf { it.maxPlayers },
        )
    }
}
