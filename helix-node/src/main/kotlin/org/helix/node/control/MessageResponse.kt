package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Confirmation payload of state-changing control API calls.
 *
 * @property message human readable confirmation.
 */
@Serializable
data class MessageResponse(val message: String)
