package org.helix.addons.moderation

import kotlinx.serialization.Serializable

/**
 * Persisted moderation configuration.
 *
 * @property warnExpiryDays how many days a warning stays active before it
 *   drops out of a player's active-warn count.
 * @property blockedWords exact words the chat filter blocks, lowercase.
 *   Deliberately a plain blocklist (not a profanity-ML system): a minimal,
 *   operator-configurable filter proportional to what this network needs.
 */
@Serializable
data class ModerationConfig(
    val warnExpiryDays: Int = 30,
    val blockedWords: List<String> = emptyList(),
)
