package de.tytoss.iguard.setback

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers when IGuard itself teleported a player back, so the resulting teleport is not treated as
 * a full teleport grace window: a setback must never buy the cheater a check-free window (the engine
 * only exempts until the client confirms the teleport).
 *
 * @property windowMillis how long after a setback the next teleport still counts as that setback.
 */
class SetbackMarks(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
    private val marks = ConcurrentHashMap<UUID, Long>()

    /** Records a setback teleport of [playerId] at [now]. */
    fun mark(playerId: UUID, now: Long) {
        marks[playerId] = now
    }

    /** True when [playerId] was set back within the window before [now]. */
    fun isRecent(playerId: UUID, now: Long): Boolean {
        val markedAt = marks[playerId] ?: return false
        if (now - markedAt in -windowMillis..windowMillis) return true
        marks.remove(playerId, markedAt)
        return false
    }

    /** Forgets the player (on quit). */
    fun remove(playerId: UUID) {
        marks.remove(playerId)
    }

    private companion object {
        /** Default window: one second covers the main-thread teleport and the packet round trip. */
        const val DEFAULT_WINDOW_MILLIS = 1000L
    }
}
