package org.helix.api.player

import kotlinx.serialization.Serializable

/**
 * A refreshed snapshot of the Minecraft-native permission nodes an
 * already-online player holds, reported by a proxy bridge whenever the
 * advertised node list changed (command-catalog/routing version bump) —
 * distinct from [PlayerEvent] so refreshing permissions never re-triggers
 * join/leave side effects for a player who never actually left.
 *
 * @property name player name.
 * @property permissions the currently granted native permission nodes.
 */
@Serializable
data class PlayerPermissionsReport(
    val name: String,
    val permissions: List<String> = emptyList(),
)
