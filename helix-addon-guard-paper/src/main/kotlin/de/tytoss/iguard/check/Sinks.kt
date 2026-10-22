package de.tytoss.iguard.check

import de.tytoss.iguard.model.SafePosition
import de.tytoss.iguard.model.ViolationRecord
import java.util.UUID

/** Receives violations at or above their check's alert level (staff alerts). */
fun interface AlertSink {
    /** Queues the violation for staff alerting. */
    fun record(record: ViolationRecord)
}

/** Executes setbacks for movement checks and remembers which teleports were setbacks. */
interface SetbackSink {
    /** Requests a teleport of the player back to [safe] after a failed [reason] check. */
    fun request(playerId: UUID, safe: SafePosition, reason: String)

    /** True when the player was set back within the last second (the next teleport is that setback). */
    fun recentSetback(playerId: UUID, now: Long): Boolean
}
