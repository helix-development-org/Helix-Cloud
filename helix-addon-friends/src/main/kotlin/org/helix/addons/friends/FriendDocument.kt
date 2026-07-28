package org.helix.addons.friends

import kotlinx.serialization.Serializable

/**
 * Persisted friendship state.
 *
 * Friendships and requests are keyed on identity keys — a player's uuid once
 * known, otherwise their lowercase name — see [FriendStore]. [displayNames]
 * carries the last-known lowercase name for every uuid key, purely for
 * showing friend lists; it plays no role in identity.
 *
 * @property friendships symmetric pairs of identity keys, each stored once.
 * @property requests pending requests as `to` identity key → set of `from`
 *  identity keys.
 * @property displayNames uuid identity key to last-known lowercase name.
 */
@Serializable
data class FriendDocument(
    val friendships: List<List<String>> = emptyList(),
    val requests: Map<String, Set<String>> = emptyMap(),
    val displayNames: Map<String, String> = emptyMap(),
)
