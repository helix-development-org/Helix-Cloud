package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Request body to toggle maintenance.
 *
 * @property enabled desired maintenance state.
 */
@Serializable
data class MaintenanceRequest(val enabled: Boolean)
