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
 * @property issuedBy name of the staff member (or `system`/addon) that
 *   issued the ban; blank for bans persisted before this field existed.
 * @property revokedAtEpochMs when the ban was lifted (pardon or natural
 *   expiry); `null` while still active.
 * @property revokedBy name of the staff member who pardoned the ban, or
 *   `null` when it was lifted by natural expiry rather than a pardon.
 */
@Serializable
data class BanEntry(
    val player: String,
    val reason: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val uuid: String? = null,
    val issuedBy: String = "",
    val revokedAtEpochMs: Long? = null,
    val revokedBy: String? = null,
) {
    /**
     * Whether the ban is active at the given time.
     *
     * @param nowEpochMs current epoch millis.
     * @return `true` while permanent or not yet expired.
     */
    fun active(nowEpochMs: Long): Boolean =
        expiresAtEpochMs == null || expiresAtEpochMs > nowEpochMs

    /**
     * A copy of this ban recorded as lifted, for the history log.
     *
     * @param nowEpochMs when the ban was lifted.
     * @param by the pardoning staff member, or `null` for a natural expiry.
     * @return the entry with [revokedAtEpochMs]/[revokedBy] set.
     */
    fun revoked(nowEpochMs: Long, by: String?): BanEntry =
        copy(revokedAtEpochMs = nowEpochMs, revokedBy = by)
}
