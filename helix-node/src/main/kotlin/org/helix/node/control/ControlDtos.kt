package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Error payload of failed control API calls.
 *
 * @property message human readable error description.
 */
@Serializable
data class ErrorResponse(val message: String)

/**
 * Confirmation payload of state-changing control API calls.
 *
 * @property message human readable confirmation.
 */
@Serializable
data class MessageResponse(val message: String)

/**
 * Log lines of a service or the node.
 *
 * @property lines newest log lines, oldest first.
 */
@Serializable
data class LogsResponse(val lines: List<String>)

/**
 * A proxy service in the proxy overview.
 *
 * @property id proxy service id.
 * @property state lifecycle state.
 * @property executor execution backend.
 * @property port listen port.
 * @property onlinePlayers connected players.
 * @property maxPlayers advertised slots.
 */
@Serializable
data class ProxySummary(
    val id: String,
    val state: String,
    val executor: String,
    val port: Int,
    val onlinePlayers: Int,
    val maxPlayers: Int,
)

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

/**
 * Aggregated proxy overview for the dashboard.
 *
 * @property maintenance whether the network rejects regular joins.
 * @property proxies all proxy services.
 * @property backends all running backend services.
 */
@Serializable
data class ProxyView(
    val maintenance: Boolean,
    val proxies: List<ProxySummary>,
    val backends: List<ProxyBackendView>,
)

/**
 * Request body to toggle maintenance.
 *
 * @property enabled desired maintenance state.
 */
@Serializable
data class MaintenanceRequest(val enabled: Boolean)

/**
 * Request body to update or reset an addon message.
 *
 * @property addonId owning addon id.
 * @property key message key.
 * @property value new template; ignored when [reset] is true.
 * @property reset restore the declared default instead of setting a value.
 */
@Serializable
data class MessageUpdate(
    val addonId: String,
    val key: String,
    val value: String = "",
    val reset: Boolean = false,
)
