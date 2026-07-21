package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Request body of `POST /services/{id}/command`.
 *
 * @property command the console command to send, without a trailing newline.
 */
@Serializable
data class ServiceCommandRequest(val command: String)
