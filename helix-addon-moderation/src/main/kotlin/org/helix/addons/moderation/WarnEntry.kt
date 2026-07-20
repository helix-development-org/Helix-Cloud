package org.helix.addons.moderation

import kotlinx.serialization.Serializable

/**
 * A recorded warning.
 *
 * @property player warned player, lowercase.
 * @property by warning moderator.
 * @property reason warning reason.
 * @property atEpochMs when the warning was issued.
 */
@Serializable
data class WarnEntry(
    val player: String,
    val by: String,
    val reason: String,
    val atEpochMs: Long,
)
