package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * A player attempting to join the network, sent by a proxy bridge.
 *
 * @property name player name.
 * @property uuid player uuid, if known.
 */
@Serializable
data class JoinRequest(
    val name: String,
    val uuid: String? = null,
)
