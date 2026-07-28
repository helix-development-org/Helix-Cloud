package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * A backend service as seen by a proxy.
 *
 * The host is resolved by the node depending on the executors of proxy and
 * backend, so the proxy can always reach the address as given.
 *
 * @property serviceId id of the backend service.
 * @property taskName task the backend belongs to.
 * @property host hostname or ip reachable from the proxy.
 * @property port port reachable from the proxy.
 * @property fallbackEligible whether players may be sent here as fallback.
 * @property maintenance whether this backend's task rejects regular joins
 *  (holders of `helix.maintenance.bypass` are exempt).
 */
@Serializable
data class RoutingBackend(
    val serviceId: String,
    val taskName: String,
    val host: String,
    val port: Int,
    val fallbackEligible: Boolean,
    val maintenance: Boolean = false,
)
