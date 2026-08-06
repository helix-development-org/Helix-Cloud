package org.helix.api.bridge

import kotlinx.serialization.Serializable

/**
 * Snapshot of the merged network resource pack, polled by proxy bridges
 * via `GET /internal/pack`.
 *
 * @property sha1 hex SHA-1 of the current pack, or `null` when no enabled
 *  addon ships a pack.
 * @property path public download path of the pack on the control API.
 */
@Serializable
data class NetworkPackInfo(
    val sha1: String? = null,
    val path: String = "/api/v1/packs/network.zip",
)
