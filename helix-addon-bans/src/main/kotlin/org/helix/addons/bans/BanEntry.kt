package org.helix.addons.bans

import kotlinx.serialization.Serializable

/**
 * A single network ban.
 *
 * Keyed internally on [uuid] once known — a rename cannot evade the ban,
 * since the lookup at join time carries the joining player's uuid, not
 * their current name. Bans of a player never seen by this node fall back
 * to a name-keyed entry ([uuid] `null`) until that uuid becomes resolvable.
 *
 * @property player banned player's last-known name, lowercase.
 * @property reason human readable reason.
 * @property createdAtEpochMs when the ban was issued.
 * @property expiresAtEpochMs when the ban ends; `null` for permanent bans.
 * @property uuid banned player's uuid, or `null` when not yet known.
 */
@Serializable
data class BanEntry(
    val player: String,
    val reason: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val uuid: String? = null,
) {
    /**
     * Whether the ban is active at the given time.
     *
     * @param nowEpochMs current epoch millis.
     * @return `true` while permanent or not yet expired.
     */
    fun active(nowEpochMs: Long): Boolean =
        expiresAtEpochMs == null || expiresAtEpochMs > nowEpochMs
}
