package de.tytoss.iguard.api

import de.tytoss.iguard.model.IncidentSnapshot
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Public read model of a player's live IGuard state (fingerprint, VLs, packet metrics). */
data class IGuardPlayerSnapshot(
    val playerId: UUID,
    val playerName: String,
    val clientVersion: String,
    val clientBrand: String?,
    val clientFamily: String,
    val clientConfidence: String,
    val clientChannels: Set<String>,
    val supported: Boolean,
    val exemptUntil: Instant?,
    val violationLevels: Map<String, Double>,
    val droppedPackets: Long,
    val lastPacketAt: Instant?,
)

/**
 * Which checks an exemption suspends.
 *
 * Movement-motivated grace windows (knockback, teleports, flight toggles) only ever need
 * [MOVEMENT]: a player who was just knocked back or teleported can still be judged for reach,
 * killaura or nuker. [ALL] is the right scope for cutscenes, minigame setups and similar
 * situations where the plugin itself moves or acts for the player.
 */
enum class IGuardExemptionScope {
    /** Every check: movement, combat, world, inventory and protocol heuristics. */
    ALL,

    /** Movement checks only; combat, world and inventory checks keep running. */
    MOVEMENT,
}

/** Handle for a granted exemption; cancelling it re-enables checks early. */
interface IGuardExemption {
    /** Player the exemption belongs to. */
    val playerId: UUID

    /** Free-text reason (shown in traces, debug output and `/iguard info`). */
    val reason: String

    /** Wall-clock end of the exemption. */
    val expiresAt: Instant

    /** Which checks the exemption suspends. */
    val scope: IGuardExemptionScope

    /** Revokes the exemption; false when it was already cancelled or replaced. */
    fun cancel(): Boolean
}

/**
 * One check verdict inside an [IGuardFrameReport].
 *
 * @property checkId check id, for example `movement.speed.a`.
 * @property failed true when the check flagged this frame (also while the signal cooldown holds it back).
 * @property counted true when the failure actually raised the violation level (false while cooling down).
 * @property weight violation weight the failure added (0 when passed or not counted).
 * @property violationLevel the check's violation level after this frame.
 * @property alertLevel the configured `alert-vl` of the check (alerts start at this level).
 * @property evidence evidence of the failure (empty when the check passed).
 */
data class IGuardCheckResult(
    val checkId: String,
    val failed: Boolean,
    val counted: Boolean,
    val weight: Double,
    val violationLevel: Double,
    val alertLevel: Double,
    val evidence: Map<String, Any>,
)

/**
 * Everything IGuard decided for one processed packet frame of one player.
 *
 * Reports are produced on the check worker threads, in packet order per player.
 *
 * @property playerId player the frame belongs to.
 * @property playerName player name at the time of the frame.
 * @property frameType frame kind: `movement`, `attack`, `block`, `inventory`, `tick`, `swing`,
 *   `action`, `abilities`, `identity`, `timeline`, `velocity`, `teleport`, `teleport-confirm`,
 *   `server-abilities` or `reset`.
 * @property receivedAt epoch millis the packet arrived at.
 * @property sequence per-player packet sequence number.
 * @property clientTick IGuard's client tick counter after the frame.
 * @property exempt true when the frame's checks were skipped (fully or for movement).
 * @property exemptions why the frame was exempt, e.g. `timed:velocity`, `tag:flight`, `stale-snapshot`.
 * @property environmentTags environment tags of the sampled snapshot (`climbable`, `liquid`, `low-tps` …).
 * @property results every check evaluated for this frame, passed and failed.
 * @property violationLevels all of the player's violation levels after the frame.
 */
data class IGuardFrameReport(
    val playerId: UUID,
    val playerName: String,
    val frameType: String,
    val receivedAt: Long,
    val sequence: Long,
    val clientTick: Long,
    val exempt: Boolean,
    val exemptions: List<String>,
    val environmentTags: Set<String>,
    val results: List<IGuardCheckResult>,
    val violationLevels: Map<String, Double>,
) {
    /** The failed subset of [results]. */
    val failures: List<IGuardCheckResult> get() = results.filter(IGuardCheckResult::failed)
}

/**
 * Callback for every processed frame of every player (see [IGuardApi.addTraceListener]).
 *
 * Invoked on IGuard's check worker threads: implementations must be fast, thread-safe and must not
 * touch the Bukkit API directly (hand work over to the main thread instead).
 */
fun interface IGuardTraceListener {
    /** Receives the report of one processed frame. */
    fun onFrame(report: IGuardFrameReport)
}

/** A running trace recording (see [IGuardApi.startTrace]). */
interface IGuardTraceSession {
    /** Traced player. */
    val playerId: UUID

    /** Label the recording was started with (sanitised, part of the file name). */
    val label: String

    /** The JSONL file the recording is written to (`plugins/IGuard/traces/<label>-<ts>.jsonl`). */
    val file: Path

    /** Wall-clock start of the recording. */
    val startedAt: Instant

    /** False once the recording was stopped (manually or by its size/duration limit). */
    val active: Boolean

    /** Frame lines queued for writing so far. */
    val frames: Long

    /** Writes a marker line (for example "now sprinting on ice"); false when no longer active. */
    fun marker(text: String): Boolean

    /** Stops the recording and returns its file, or null when it was already stopped. */
    fun stop(): Path?
}

/** Public service other plugins can obtain via Bukkit's ServicesManager to integrate with IGuard. */
interface IGuardApi {
    /** Live state for an online player, or null before any packet was observed. */
    fun snapshot(playerId: UUID): IGuardPlayerSnapshot?

    /** The player's most recent open incident (case), or null. */
    fun latestIncident(playerId: UUID): IncidentSnapshot?

    /**
     * Temporarily exempts a player from ALL checks (movement, combat, world, inventory).
     *
     * Exemptions of the same scope do not stack: the longest one wins, a shorter grant never
     * shortens an existing one. Cancel the returned handle to end it early. Prefer the scoped
     * overload with [IGuardExemptionScope.MOVEMENT] when only the player's movement is affected
     * (knockback, launch pads, teleport effects), so combat cheats stay detectable.
     *
     * @param playerId player to exempt.
     * @param duration positive length of the exemption.
     * @param reason non-blank reason, shown in traces and debug output.
     * @return the exemption handle.
     */
    fun exempt(playerId: UUID, duration: Duration, reason: String): IGuardExemption

    /**
     * Temporarily exempts a player from the checks of [scope] (see [exempt]).
     *
     * @param playerId player to exempt.
     * @param duration positive length of the exemption.
     * @param reason non-blank reason, shown in traces and debug output.
     * @param scope which checks to suspend.
     * @return the exemption handle.
     */
    fun exempt(playerId: UUID, duration: Duration, reason: String, scope: IGuardExemptionScope): IGuardExemption

    /** True while the player holds an active exemption of any scope. */
    fun isExempt(playerId: UUID): Boolean

    /** Issue a ban through IGuard's coordinator (audit + notify + the configured ban provider). */
    fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String)

    /** Lift a ban through IGuard's coordinator. */
    fun unban(playerId: UUID, playerName: String, actor: String)

    /** Every check id IGuard knows (`movement.fly.a`, `combat.reach.a` …). */
    fun checkIds(): Set<String>

    /**
     * Starts recording a JSONL trace of every frame IGuard evaluates for the player (inputs,
     * environment, evaluator internals, verdicts). A trace already running for the player is
     * stopped first. The recording ends on [stopTrace], on quit, or when the configured
     * `trace.max-bytes`/`trace.max-seconds` limit is reached.
     *
     * @param playerId player to trace.
     * @param label short label for the file name (`a-z0-9._-`, other characters become `_`).
     * @return the running session.
     */
    fun startTrace(playerId: UUID, label: String): IGuardTraceSession

    /** Stops the player's trace and returns its file, or null when none was running. */
    fun stopTrace(playerId: UUID): Path?

    /** Writes a marker line into the player's running trace; false when none is running. */
    fun traceMarker(playerId: UUID, text: String): Boolean

    /** The player's running trace, or null. */
    fun activeTrace(playerId: UUID): IGuardTraceSession?

    /**
     * Registers a listener receiving an [IGuardFrameReport] for every processed frame of every
     * player. Close the returned handle to unregister.
     */
    fun addTraceListener(listener: IGuardTraceListener): AutoCloseable
}
