package de.tytoss.iguard.trace

import de.tytoss.iguard.api.IGuardCheckResult
import de.tytoss.iguard.api.IGuardFrameReport
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.ClientAbilitiesFrame
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.ClientIdentityFrame
import de.tytoss.iguard.model.ClientTickFrame
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.InventoryClickFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.PacketFrame
import de.tytoss.iguard.model.ResetFrame
import de.tytoss.iguard.model.ServerAbilitiesFrame
import de.tytoss.iguard.model.ServerTeleportFrame
import de.tytoss.iguard.model.ServerVelocityFrame
import de.tytoss.iguard.model.SwingFrame
import de.tytoss.iguard.model.TeleportConfirmFrame
import de.tytoss.iguard.model.TimelineFrame
import de.tytoss.iguard.model.Vec3
import java.util.UUID

/**
 * Snapshot of an attack target's sampled frames that the reach/rotation checks judged a hit against.
 *
 * @property entityId target entity id from the attack packet.
 * @property playerId target player.
 * @property playerName target player name.
 * @property frames the (interpolated) target frames, oldest first.
 */
data class TraceTarget(
    val entityId: Int,
    val playerId: UUID,
    val playerName: String,
    val frames: List<EnvironmentFrame>,
)

/**
 * Everything the check engine knows about one processed frame: the packet, the environment it was
 * judged against, the evaluator internals and every verdict. Produced only while somebody observes
 * the player (trace recording, debug view or API listener).
 *
 * @property frame the packet frame.
 * @property playerName player name.
 * @property environment the sampled environment used for the frame, or null when none was available.
 * @property clientTick IGuard's client tick counter after the frame.
 * @property exempt true when the frame's checks were skipped.
 * @property exemptions why the frame was exempt.
 * @property results every evaluated check (passed and failed).
 * @property violationLevels all violation levels after the frame.
 * @property delta position delta of a movement frame, null otherwise.
 * @property physicalSupport whether a collision box supported the player's feet (movement frames).
 * @property internals evaluator state after the frame (predictions, uncertainty, airTicks …).
 * @property limits limits computed for the frame (speed limit, fly ceiling, climb ceiling …).
 * @property metrics check-specific measurements (reach distance, look dot, client TPS …).
 * @property targets attack targets the combat checks judged against.
 */
class FrameTrace(
    val frame: PacketFrame,
    val playerName: String,
    val environment: EnvironmentFrame?,
    val clientTick: Long,
    val exempt: Boolean,
    val exemptions: List<String>,
    val results: List<IGuardCheckResult>,
    val violationLevels: Map<String, Double>,
    val delta: Vec3?,
    val physicalSupport: Boolean?,
    val internals: Map<String, Any>,
    val limits: Map<String, Double>,
    val metrics: Map<String, Any>,
    val targets: List<TraceTarget>,
) {
    /** The public listener view of this trace. */
    fun toReport(): IGuardFrameReport = IGuardFrameReport(
        frame.playerId,
        playerName,
        frameType(frame),
        frame.receivedAt,
        frame.sequence,
        clientTick,
        exempt,
        exemptions,
        environment?.environmentTags ?: emptySet(),
        results,
        violationLevels,
    )

    companion object {
        /**
         * The stable frame-type name used in reports and trace files.
         *
         * @param frame any packet frame.
         * @return the frame type, e.g. `movement` or `attack`.
         */
        fun frameType(frame: PacketFrame): String = when (frame) {
            is MovementFrame -> "movement"
            is AttackFrame -> "attack"
            is SwingFrame -> "swing"
            is ClientActionFrame -> "action"
            is ClientAbilitiesFrame -> "abilities"
            is ClientTickFrame -> "tick"
            is ClientIdentityFrame -> "identity"
            is TimelineFrame -> "timeline"
            is BlockActionFrame -> "block"
            is InventoryClickFrame -> "inventory"
            is ServerVelocityFrame -> "velocity"
            is ServerTeleportFrame -> "teleport"
            is TeleportConfirmFrame -> "teleport-confirm"
            is ServerAbilitiesFrame -> "server-abilities"
            is ResetFrame -> "reset"
        }
    }
}

/** Receives [FrameTrace]s from the check engine for the players it asks for. */
interface FrameObserver {
    /** Whether the engine should build a trace for this player's current frame (hot path: keep it cheap). */
    fun wants(playerId: UUID): Boolean

    /** Consumes one frame trace (called on the player's check worker thread). */
    fun observe(trace: FrameTrace)

    /** Observer that never wants anything. */
    object None : FrameObserver {
        override fun wants(playerId: UUID): Boolean = false

        override fun observe(trace: FrameTrace) = Unit
    }
}
