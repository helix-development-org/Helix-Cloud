package org.helix.addons.moderation

import kotlinx.serialization.Serializable

/**
 * A recorded warning.
 *
 * Tagged with [uuid] once known, so a rename does not detach the warning
 * from the player it was issued to — see [WarnStore].
 *
 * @property player warned player's last-known name, lowercase.
 * @property by warning moderator.
 * @property reason warning reason.
 * @property atEpochMs when the warning was issued.
 * @property uuid the warned player's uuid, or `null` when not yet known.
 */
@Serializable
data class WarnEntry(
    val player: String,
    val by: String,
    val reason: String,
    val atEpochMs: Long,
    val uuid: String? = null,
)
