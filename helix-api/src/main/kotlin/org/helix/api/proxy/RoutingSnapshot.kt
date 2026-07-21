package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * Complete routing view a proxy service polls from the node.
 *
 * @property backends all running backend services with resolved addresses.
 * @property maintenance whether the network rejects regular joins.
 * @property networkName display name of the network (`{network}` placeholder).
 * @property maintenanceScreen configurable disconnect screen shown to players
 *  rejected because of maintenance (MiniMessage, may be multi-line).
 * @property serverFullScreen configurable disconnect screen shown when the
 *  network is full (MiniMessage, may be multi-line).
 */
@Serializable
data class RoutingSnapshot(
    val backends: List<RoutingBackend> = emptyList(),
    val maintenance: Boolean = false,
    val networkName: String = "",
    val maintenanceScreen: String = "",
    val serverFullScreen: String = "",
)
