package org.helix.addons.moderation

import kotlinx.serialization.Serializable

/**
 * A single network mute.
 *
 * @property player muted player name, lowercase.
 * @property reason human readable reason.
 * @property createdAtEpochMs when the mute was issued.
 * @property expiresAtEpochMs when the mute ends; `null` for permanent mutes.
 * @property issuedBy name of the staff member who issued the mute.
 */
@Serializable
data class MuteEntry(
    val player: String,
    val reason: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val issuedBy: String = "",
) {
    /**
     * Whether the mute is active at the given time.
     *
     * @param nowEpochMs current epoch millis.
     * @return `true` while permanent or not yet expired.
     */
    fun active(nowEpochMs: Long): Boolean =
        expiresAtEpochMs == null || expiresAtEpochMs > nowEpochMs
}
