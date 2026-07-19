package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * Complete routing view a proxy service polls from the node.
 *
 * @property backends all running backend services with resolved addresses.
 * @property maintenance whether the network rejects regular joins.
 */
@Serializable
data class RoutingSnapshot(
    val backends: List<RoutingBackend> = emptyList(),
    val maintenance: Boolean = false,
)
