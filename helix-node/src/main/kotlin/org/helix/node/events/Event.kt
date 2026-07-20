package org.helix.node.events

import kotlinx.serialization.Serializable

/**
 * A single node event for the dashboard timeline.
 *
 * @property epochMs when the event happened.
 * @property category coarse grouping, for example `service`, `player`,
 *   `moderation`, `proxy`, `task`, `node`.
 * @property level severity: `info`, `warn` or `error`.
 * @property message human readable description.
 */
@Serializable
data class Event(
    val epochMs: Long,
    val category: String,
    val level: String,
    val message: String,
)
