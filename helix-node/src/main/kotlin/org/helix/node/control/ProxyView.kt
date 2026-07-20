package org.helix.node.control

import kotlinx.serialization.Serializable

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
