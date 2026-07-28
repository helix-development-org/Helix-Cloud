package org.helix.addons.maprotation

import kotlinx.serialization.Serializable

/**
 * One configured rotation's maps/worlds and cursor.
 *
 * @property maps ordered map/world names, cycled in this order.
 * @property currentIndex index into [maps] of the current entry.
 */
@Serializable
data class RotationState(
    val maps: List<String> = emptyList(),
    val currentIndex: Int = 0,
)

/**
 * Persisted rotation configuration and state.
 *
 * @property rotations rotation id (lowercase) to its [RotationState].
 */
@Serializable
data class RotationsDocument(
    val rotations: Map<String, RotationState> = emptyMap(),
)
