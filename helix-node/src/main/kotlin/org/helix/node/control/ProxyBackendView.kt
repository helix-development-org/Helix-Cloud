package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * A backend service as routed to proxies.
 *
 * @property id backend service id.
 * @property task task the backend belongs to.
 * @property state lifecycle state.
 * @property host resolved host for a docker-network view.
 * @property port backend port.
 * @property onlinePlayers connected players.
 * @property fallbackEligible whether it may serve as fallback/lobby.
 */
@Serializable
data class ProxyBackendView(
    val id: String,
    val task: String,
    val state: String,
    val host: String,
    val port: Int,
    val onlinePlayers: Int,
    val fallbackEligible: Boolean,
)
