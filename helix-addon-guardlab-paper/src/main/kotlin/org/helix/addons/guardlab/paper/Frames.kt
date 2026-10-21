package org.helix.addons.guardlab.paper

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One check verdict of a [FrameSample] (GuardLab's copy of IGuard's `IGuardCheckResult`).
 *
 * @property check check id.
 * @property failed the check flagged the frame (also while its signal cooldown holds it back).
 * @property counted the failure raised the violation level.
 * @property weight violation weight the failure added (0 when passed or not counted).
 * @property level the check's violation level after the frame.
 * @property alertLevel the check's configured `alert-vl`.
 */
data class CheckSample(
    val check: String,
    val failed: Boolean,
    val counted: Boolean,
    val weight: Double,
    val level: Double,
    val alertLevel: Double,
)

/**
 * One frame IGuard processed (GuardLab's copy of IGuard's `IGuardFrameReport`, so the evaluation can be
 * tested without IGuard on the classpath).
 *
 * @property playerId player of the frame.
 * @property type frame kind (`movement`, `attack`, `block`, `tick` …).
 * @property clientTick IGuard's client tick counter after the frame.
 * @property exempt the frame's checks were skipped (for movement frames: the movement checks; for
 *   attack/block frames: the action checks).
 * @property exemptions raw reasons (`timed:movement:velocity`, `tag:vehicle`, `stale-snapshot` …).
 * @property tags environment tags of the sampled snapshot.
 * @property results every check evaluated for the frame.
 */
data class FrameSample(
    val playerId: UUID,
    val type: String,
    val clientTick: Long,
    val exempt: Boolean,
    val exemptions: List<String>,
    val tags: Set<String>,
    val results: List<CheckSample>,
)

/**
 * How often a reason or tag was active during a scenario.
 *
 * @property name readable label, e.g. `vehicle (MOVEMENT)` or `liquid`.
 * @property ticks client ticks it was active in.
 * @property share [ticks] relative to the reference ticks (0..1): the checked ticks for exemption
 *   reasons, every tick with a frame for environment tags.
 */
@Serializable
data class Share(val name: String, val ticks: Int, val share: Double)

/**
 * What IGuard did with one check during a scenario, read from its frame reports.
 *
 * @property check check id.
 * @property evaluated frames the check was evaluated in.
 * @property failed frames it flagged (including those its cooldown did not count).
 * @property counted flagged frames that raised the violation level.
 * @property weight summed violation weight of the counted frames.
 * @property maxVl highest violation level seen (absolute).
 * @property alertVl the check's alert level as IGuard reported it (`NaN` when unknown).
 */
@Serializable
data class CheckActivity(
    val check: String,
    val evaluated: Int,
    val failed: Int,
    val counted: Int,
    val weight: Double,
    val maxVl: Double,
    val alertVl: Double,
)

/**
 * Frame-report summary of one scenario: how much IGuard actually checked and why it did not.
 *
 * Shares are measured in IGuard client ticks. The checked ticks are those with a movement, attack or
 * block frame (only these frames carry exemption reasons); [movementExemptShare] relates exempt movement
 * frames to the ticks with a movement frame.
 *
 * @property frames frame reports received.
 * @property ticks checked client ticks.
 * @property movementTicks client ticks with a movement frame.
 * @property allExemptShare share of checked ticks under an ALL-scope exemption (every check suspended).
 * @property movementExemptShare share of movement ticks whose movement checks were skipped, null without movement frames.
 * @property exemptions active exemption reasons, most frequent first.
 * @property tags environment tags, most frequent first (relative to all ticks with a frame).
 * @property checks per-check activity, by check id.
 */
@Serializable
data class FrameSummary(
    val frames: Long,
    val ticks: Int,
    val movementTicks: Int,
    val allExemptShare: Double,
    val movementExemptShare: Double?,
    val exemptions: List<Share>,
    val tags: List<Share>,
    val checks: List<CheckActivity>,
) {
    /**
     * Activity of one check.
     *
     * @param check check id.
     * @return the activity, null when IGuard never evaluated it.
     */
    fun activity(check: String): CheckActivity? = checks.firstOrNull { it.check == check }
}

/** Reads IGuard's raw exemption reasons. */
object ExemptionReasons {
    /** Tags that suspend every check (mirror of IGuard's `EnvironmentTags.ACTION_EXEMPTING`). */
    val ALL_SCOPE_TAGS: Set<String> = setOf("bypass", "dead", "sleeping", "game-mode")

    /** Frame types that carry exemption reasons (movement path and action path). */
    val CHECKED_FRAMES: Set<String> = setOf("movement", "attack", "block")

    /**
     * Whether a raw reason suspends every check (timed ALL exemption or an action-exempting tag).
     *
     * @param raw reason as IGuard reports it.
     * @return true for ALL scope.
     */
    fun isAllScope(raw: String): Boolean =
        raw.startsWith("timed:all:") || (raw.startsWith("tag:") && raw.removePrefix("tag:") in ALL_SCOPE_TAGS)

    /**
     * Readable label with scope: `timed:movement:velocity` → `velocity (MOVEMENT)`, `tag:vehicle` →
     * `vehicle (MOVEMENT)`, `timed:all:cutscene` → `cutscene (ALL)`, `stale-snapshot` → `stale-snapshot (MOVEMENT)`.
     *
     * @param raw reason as IGuard reports it.
     * @return the label.
     */
    fun label(raw: String): String {
        val name = when {
            raw.startsWith("timed:all:") -> raw.removePrefix("timed:all:")
            raw.startsWith("timed:movement:") -> raw.removePrefix("timed:movement:")
            raw.startsWith("timed:") -> raw.removePrefix("timed:")
            raw.startsWith("tag:") -> raw.removePrefix("tag:")
            else -> raw
        }
        return "$name (${if (isAllScope(raw)) "ALL" else "MOVEMENT"})"
    }
}

/**
 * Collects IGuard frame reports of one player during one scenario.
 *
 * [record] is called on IGuard's worker threads, everything else on the main thread; all access is
 * synchronised.
 *
 * @property playerId the traced player; frames of other players are ignored.
 * @property baseline violation levels at scenario start (peaks are reported above them).
 */
class FrameStats(val playerId: UUID, private val baseline: Map<String, Double> = emptyMap()) {
    private class Activity {
        var evaluated = 0
        var failed = 0
        var counted = 0
        var weight = 0.0
        var maxVl = 0.0
        var alertVl = Double.NaN
    }

    private val lock = Any()
    private var frames = 0L
    private val allTicks = HashSet<Long>()
    private val checkedTicks = HashSet<Long>()
    private val movementTicks = HashSet<Long>()
    private val movementExemptTicks = HashSet<Long>()
    private val allExemptTicks = HashSet<Long>()
    private val reasons = HashMap<String, MutableSet<Long>>()
    private val tags = HashMap<String, MutableSet<Long>>()
    private val checks = HashMap<String, Activity>()

    /** Frame reports received so far. */
    val frameCount: Long get() = synchronized(lock) { frames }

    /**
     * Records one frame (thread-safe).
     *
     * @param frame the frame; ignored when it belongs to another player.
     */
    fun record(frame: FrameSample) {
        if (frame.playerId != playerId) return
        synchronized(lock) {
            frames++
            val tick = frame.clientTick
            allTicks += tick
            frame.tags.forEach { tags.getOrPut(it) { HashSet() } += tick }
            if (frame.type in ExemptionReasons.CHECKED_FRAMES) {
                checkedTicks += tick
                frame.exemptions.forEach { raw ->
                    reasons.getOrPut(ExemptionReasons.label(raw)) { HashSet() } += tick
                    if (ExemptionReasons.isAllScope(raw)) allExemptTicks += tick
                }
            }
            if (frame.type == "movement") {
                movementTicks += tick
                if (frame.exempt) movementExemptTicks += tick
            }
            frame.results.forEach { result ->
                val activity = checks.getOrPut(result.check) { Activity() }
                activity.evaluated++
                if (result.failed) activity.failed++
                if (result.counted) {
                    activity.counted++
                    activity.weight += result.weight
                }
                if (result.level > activity.maxVl) activity.maxVl = result.level
                if (!result.alertLevel.isNaN()) activity.alertVl = result.alertLevel
            }
        }
    }

    /**
     * The summary of everything recorded.
     *
     * @return the summary.
     */
    fun summary(): FrameSummary = synchronized(lock) {
        val checked = checkedTicks.size
        val all = allTicks.size
        FrameSummary(
            frames = frames,
            ticks = checked,
            movementTicks = movementTicks.size,
            allExemptShare = share(allExemptTicks.size, checked),
            movementExemptShare = if (movementTicks.isEmpty()) null else share(movementExemptTicks.size, movementTicks.size),
            exemptions = shares(reasons, checked),
            tags = shares(tags, all),
            checks = checks.entries.sortedBy { it.key }.map { (check, a) ->
                CheckActivity(check, a.evaluated, a.failed, a.counted, a.weight, a.maxVl, a.alertVl)
            },
        )
    }

    /**
     * Per-check observations for the [Evaluator]: every check that failed at least once. `flags` are the
     * counted frames, `total` their summed weight, `peak` the highest level above the start baseline.
     *
     * @return observations by check id.
     */
    fun observations(): List<CheckObservation> = synchronized(lock) {
        checks.entries.filter { it.value.failed > 0 }.sortedBy { it.key }.map { (check, a) ->
            CheckObservation(
                check = check,
                flags = a.counted,
                total = a.weight,
                peak = (a.maxVl - (baseline[check] ?: 0.0)).coerceAtLeast(0.0),
                failed = a.failed,
                maxVl = a.maxVl,
            )
        }
    }

    private fun shares(source: Map<String, Set<Long>>, total: Int): List<Share> =
        source.map { (name, ticks) -> Share(name, ticks.size, share(ticks.size, total)) }
            .sortedWith(compareByDescending<Share> { it.ticks }.thenBy { it.name })

    private fun share(part: Int, total: Int): Double = if (total == 0) 0.0 else part.toDouble() / total
}
