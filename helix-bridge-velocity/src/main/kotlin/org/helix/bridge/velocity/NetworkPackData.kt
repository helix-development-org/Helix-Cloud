package org.helix.bridge.velocity

import kotlinx.serialization.Serializable

/**
 * The node's merged network resource pack, as served by
 * `GET /api/v1/internal/pack`.
 *
 * @property sha1 hex SHA-1 of the current pack, or `null` when no enabled
 *  addon ships a pack.
 * @property path public download path of the pack on the control API.
 */
@Serializable
data class NetworkPackData(
    val sha1: String? = null,
    val path: String = "/api/v1/packs/network.zip",
)
