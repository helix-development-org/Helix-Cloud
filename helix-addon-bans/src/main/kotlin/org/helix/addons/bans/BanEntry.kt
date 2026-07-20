package org.helix.addons.bans

import kotlinx.serialization.Serializable

/**
 * A single network ban.
 *
 * @property player banned player name, lowercase.
 * @property reason human readable reason.
 * @property createdAtEpochMs when the ban was issued.
 * @property expiresAtEpochMs when the ban ends; `null` for permanent bans.
 */
@Serializable
data class BanEntry(
    val player: String,
    val reason: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
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
