package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Log lines of a service or the node.
 *
 * @property lines newest log lines, oldest first.
 */
@Serializable
data class LogsResponse(val lines: List<String>)
