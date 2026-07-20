package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Error payload of failed control API calls.
 *
 * @property message human readable error description.
 */
@Serializable
data class ErrorResponse(val message: String)
