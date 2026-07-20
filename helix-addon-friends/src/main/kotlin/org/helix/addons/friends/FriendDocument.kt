package org.helix.addons.friends

import kotlinx.serialization.Serializable

/**
 * Persisted friendship state.
 *
 * @property friendships symmetric pairs, each stored once, names lowercase.
 * @property requests pending requests as `to` → set of `from` names.
 */
@Serializable
data class FriendDocument(
    val friendships: List<List<String>> = emptyList(),
    val requests: Map<String, Set<String>> = emptyMap(),
)
