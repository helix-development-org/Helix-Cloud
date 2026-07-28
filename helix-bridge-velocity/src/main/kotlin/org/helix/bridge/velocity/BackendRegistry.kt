package org.helix.bridge.velocity

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import org.helix.api.proxy.RoutingSnapshot
import org.slf4j.Logger

/**
 * Mirrors the node routing snapshot into Velocity's server registry.
 *
 * Only servers managed by this bridge are touched; servers from the static
 * `velocity.toml` are left alone.
 *
 * @property proxy the Velocity proxy.
 * @property logger bridge logger.
 */
class BackendRegistry(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private val managed = ConcurrentHashMap<String, InetSocketAddress>()
    private val fallbackEligible = ConcurrentHashMap.newKeySet<String>()
    private val maintenanceBackends = ConcurrentHashMap.newKeySet<String>()

    /**
     * Applies a routing snapshot: registers new backends, updates changed
     * addresses and unregisters vanished ones.
     *
     * @param snapshot the latest routing view from the node.
     */
    fun sync(snapshot: RoutingSnapshot) {
        val desired = snapshot.backends.associateBy { it.serviceId }
        desired.values.forEach { backend ->
            val address = InetSocketAddress.createUnresolved(backend.host, backend.port)
            val known = managed[backend.serviceId]
            if (known != address) {
                proxy.getServer(backend.serviceId).ifPresent { existing ->
                    proxy.unregisterServer(existing.serverInfo)
                }
                proxy.registerServer(ServerInfo(backend.serviceId, address))
                managed[backend.serviceId] = address
                logger.info("Registered backend {} at {}:{}", backend.serviceId, backend.host, backend.port)
            }
            if (backend.fallbackEligible) {
                fallbackEligible.add(backend.serviceId)
            } else {
                fallbackEligible.remove(backend.serviceId)
            }
            if (backend.maintenance) {
                maintenanceBackends.add(backend.serviceId)
            } else {
                maintenanceBackends.remove(backend.serviceId)
            }
        }
        managed.keys.filter { it !in desired }.forEach { vanished ->
            proxy.getServer(vanished).ifPresent { proxy.unregisterServer(it.serverInfo) }
            managed.remove(vanished)
            fallbackEligible.remove(vanished)
            maintenanceBackends.remove(vanished)
            logger.info("Unregistered backend {}", vanished)
        }
    }

    /**
     * Picks the least loaded fallback backend.
     *
     * @param exclude server name to skip, for example the origin of a kick.
     * @param bypassMaintenance whether a maintenance-flagged backend may
     *  still be picked (holders of `helix.maintenance.bypass`).
     * @return the registered server, or `null` when no fallback exists.
     */
    fun fallback(exclude: String? = null, bypassMaintenance: Boolean = false): RegisteredServer? {
        val candidates = managed.keys.mapNotNull { name ->
            proxy.getServer(name).map { server ->
                FallbackCandidate(
                    name = name,
                    players = server.playersConnected.size,
                    fallbackEligible = name in fallbackEligible,
                    maintenance = name in maintenanceBackends,
                )
            }.orElse(null)
        }
        val selected = FallbackSelector.select(candidates, exclude, bypassMaintenance) ?: return null
        return proxy.getServer(selected).orElse(null)
    }

    /**
     * Whether a backend's task is currently flagged for maintenance.
     *
     * @param name the backend's registered server name.
     * @return `true` when the backend rejects regular joins.
     */
    fun isMaintenance(name: String): Boolean = name in maintenanceBackends

    /**
     * Lists the names of all managed backends.
     *
     * @return backend names sorted alphabetically.
     */
    fun backendNames(): List<String> = managed.keys.sorted()
}
