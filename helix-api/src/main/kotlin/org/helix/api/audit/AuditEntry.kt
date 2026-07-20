package org.helix.api.audit

import kotlinx.serialization.Serializable

/**
 * A single audit record.
 *
 * The audit log captures everything that happens on the node — HTTP
 * requests, action invocations, auth attempts, service lifecycle, player
 * activity and more — for a complete, durable trail.
 *
 * @property epochMs when it happened.
 * @property category coarse grouping, for example `http`, `action`,
 *   `auth`, `service`, `player`, `proxy`, `task`, `node`, `moderation`.
 * @property actor who caused it: an auth principal, an action source or
 *   `system`.
 * @property summary human readable description.
 * @property outcome `ok`, `denied`, `error` or `info`.
 */
@Serializable
data class AuditEntry(
    val epochMs: Long,
    val category: String,
    val actor: String,
    val summary: String,
    val outcome: String,
)
