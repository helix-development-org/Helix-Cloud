package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Staff-facing aggregate view of a single player, combining live presence
 * with every addon's per-player data (bans, warns, permissions, clan,
 * economy, …) — the same aggregation the GDPR export action uses, reused
 * here rather than re-derived per addon.
 *
 * @property name the looked-up player name.
 * @property online whether the player is currently connected.
 * @property uuid the player's uuid, when online.
 * @property proxyServiceId the proxy the player is connected through, when online.
 * @property joinedAtEpochMs when the current session started, when online.
 * @property sources owning addon id to that addon's raw JSON export, for
 *  example `bans`, `permissions`, `clan`, `economy`, `moderation`.
 */
@Serializable
data class PlayerLookupView(
    val name: String,
    val online: Boolean,
    val uuid: String? = null,
    val proxyServiceId: String? = null,
    val joinedAtEpochMs: Long? = null,
    val sources: Map<String, String> = emptyMap(),
)
