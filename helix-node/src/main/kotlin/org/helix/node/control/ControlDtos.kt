package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Error payload of failed control API calls.
 *
 * @property message human readable error description.
 */
@Serializable
data class ErrorResponse(val message: String)

/**
 * Confirmation payload of state-changing control API calls.
 *
 * @property message human readable confirmation.
 */
@Serializable
data class MessageResponse(val message: String)

/**
 * Log lines of a service.
 *
 * @property lines newest log lines, oldest first.
 */
@Serializable
data class LogsResponse(val lines: List<String>)
