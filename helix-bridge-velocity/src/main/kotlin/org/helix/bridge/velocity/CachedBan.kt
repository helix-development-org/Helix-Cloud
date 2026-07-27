package org.helix.bridge.velocity

import kotlinx.serialization.Serializable

/**
 * One entry of the locally cached ban snapshot, decoded from whatever ban addon is installed
 * (currently `helix-addon-bans`'s `BanEntry`). Only the fields this bridge needs for the
 * node-down fallback check; unknown fields in the source JSON are ignored on decode.
 *
 * @property player banned player name, lowercase.
 * @property reason human readable reason, shown if the cached ban is enforced.
 * @property expiresAtEpochMs when the ban ends; `null` for permanent bans.
 */
@Serializable
data class CachedBan(
    val player: String,
    val reason: String = "",
    val expiresAtEpochMs: Long? = null,
)
