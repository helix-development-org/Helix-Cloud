package de.tytoss.iguard.snapshot

import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.PlayerView
import de.tytoss.iguard.model.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val HISTORY_SIZE = 21

/** Lock-free store of the latest [PlayerView] per player (plus a bounded frame history). */
class SnapshotStore {
    private val players = ConcurrentHashMap<UUID, PlayerView>()
    private val entityIds = ConcurrentHashMap<Int, UUID>()

    /** Publishes a fresh main-thread [frame] for the player, appending it to the bounded history. */
    fun update(playerId: UUID, playerName: String, entityId: Int, frame: EnvironmentFrame) {
        players.compute(playerId) { _, previous ->
            if (previous != null && previous.entityId != entityId) entityIds.remove(previous.entityId, playerId)
            // Build the bounded history in a single allocation (the previous "(list + frame).takeLast"
            // allocated twice per update). Stays an immutable snapshot so concurrent readers are safe.
            val prior = previous?.history
            val history = if (prior.isNullOrEmpty()) {
                listOf(frame)
            } else {
                val start = maxOf(0, prior.size + 1 - HISTORY_SIZE)
                ArrayList<EnvironmentFrame>(prior.size + 1 - start).apply {
                    for (index in start until prior.size) add(prior[index])
                    add(frame)
                }
            }
            PlayerView(playerId, playerName, entityId, frame, history)
        }
        entityIds[entityId] = playerId
    }

    /** Drops the player's view and entity-id mapping (on quit). */
    fun remove(playerId: UUID) {
        val removed = players.remove(playerId) ?: return
        entityIds.remove(removed.entityId, playerId)
    }

    /** The player's latest view, or null when not tracked. */
    fun view(playerId: UUID) = players[playerId]

    /** The view of the player owning the given entity id (attack targets), or null. */
    fun target(entityId: Int) = entityIds[entityId]?.let(players::get)

    /** The frame at [timestamp], linearly interpolated between the surrounding history samples. */
    fun frameAt(view: PlayerView, timestamp: Long): EnvironmentFrame {
        val before = view.history.lastOrNull { it.capturedAt <= timestamp }
        val after = view.history.firstOrNull { it.capturedAt >= timestamp }
        if (before == null) return after ?: view.current
        if (after == null || before === after || after.capturedAt == before.capturedAt) return before
        val factor = ((timestamp - before.capturedAt).toDouble() / (after.capturedAt - before.capturedAt)).coerceIn(0.0, 1.0)
        return before.copy(
            capturedAt = timestamp,
            position = before.position.interpolate(after.position, factor),
            yaw = (before.yaw + (after.yaw - before.yaw) * factor).toFloat(),
            pitch = (before.pitch + (after.pitch - before.pitch) * factor).toFloat(),
            entityBox = before.entityBox.interpolate(after.entityBox, factor)
        )
    }

    /** Number of tracked players. */
    fun size() = players.size
}

private fun Vec3.interpolate(other: Vec3, factor: Double) = Vec3(
    x + (other.x - x) * factor,
    y + (other.y - y) * factor,
    z + (other.z - z) * factor
)

private fun Box.interpolate(other: Box, factor: Double) = Box(
    minX + (other.minX - minX) * factor,
    minY + (other.minY - minY) * factor,
    minZ + (other.minZ - minZ) * factor,
    maxX + (other.maxX - maxX) * factor,
    maxY + (other.maxY - maxY) * factor,
    maxZ + (other.maxZ - maxZ) * factor
)
