package de.tytoss.iguard.check

import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.EnvironmentTags
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.Vec3
import de.tytoss.iguard.profile.VersionProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

internal data class MovementEvaluation(val failures: List<CheckFailure>, val supporting: Boolean)

// Vanilla climb speed: a climbing entity's vertical velocity is set to 0.2 and then loses gravity and
// drag before the next move, so a steady climb rises (0.2 - 0.08) * 0.98 blocks per tick.
private const val CLIMB_SPEED = 0.1176

// Consecutive frames hovering level just above a liquid before the surface variant of the Jesus check
// fires. A legit jump across water is level for at most two ticks around its apex.
private const val JESUS_HOVER_FRAMES = 6

// Airborne ticks required before flagging ground-spoof/nofall while the server is lagging (sampled
// support is unreliable under lag). Well above vanilla fall onset so real nofall still trips.
private const val LAG_NOFALL_AIRTICKS = 12

// Offset (blocks outside the physically possible move) that maps to a violation weight of 1.0. A
// clear cheat produces a multiple of this; a borderline offset a fraction, so the VL self-calibrates.
private const val FLY_OFFSET_REF = 0.1
private const val SPEED_OFFSET_REF = 0.1

/** Clean frames per sustained-speed window (one second of movement). */
private const val SPEED_WINDOW = 20

/** Average observed/vanilla ratio above which a window flags (legit movement stays at or below 1.0). */
private const val SPEED_WINDOW_RATIO = 1.06

/** Excess ratio that weighs 1.0 (×1.15 → about 3 per window). */
private const val SPEED_WINDOW_RATIO_REF = 0.05

/** Smallest summed excess (blocks) of a flagging window, so slow creeping never flags on rounding. */
private const val SPEED_WINDOW_MIN_EXCESS = 0.2

/** Frames slower than this (blocks/tick) do not count – standing and creeping say nothing about a boost. */
private const val SPEED_WINDOW_MIN_MOVE = 0.1

// Elytra-fly ("no gravity while gliding") thresholds — see MovementEvaluator.elytraFailure for the
// physics reasoning. Deliberately generous: a hair of positive dy is still "not descending" (numerical
// jitter / a leveling-out glide), and the streak length survives realistic rocket-boost chaining.
private const val ELYTRA_DESCENT_EPSILON = 0.02
private const val ELYTRA_STREAK_FRAMES = 100

private data class HorizontalProjection(
    val limit: Double,
    val vanilla: Double,
    val nextVelocity: Double,
    val acceleration: Double,
    val friction: Double,
    val jumpImpulse: Double,
    val takeoffAllowance: Double,
)

internal class MovementEvaluator {
    /** Runs the movement checks for one frame and returns the failures + support flag. */
    fun evaluate(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        profile: VersionProfile,
        state: PlayerState,
        laggy: Boolean = false,
        limits: MutableMap<String, Double>? = null,
    ): MovementEvaluation {
        val ticks = state.positionGapTicks.coerceIn(1, 2)
        val supporting = supports(frame.position, environment)
        val tags = environment.environmentTags
        val failures = buildList {
            if (EnvironmentTags.LIQUID in tags) {
                state.surfaceHoverStreak = 0
                jesusFailure(frame, delta, state, supporting)?.let(::add)
            } else {
                flyFailure(frame, delta, environment, profile, state, ticks, supporting, laggy, limits)?.let(::add)
                speedFailure(delta, environment, state, ticks, supporting, laggy, limits)?.let(::add)
                noFallFailure(frame, delta, environment, state, supporting, laggy)?.let(::add)
                phaseFailure(frame, delta, environment, state, laggy)?.let(::add)
                stepFailure(delta, environment, state, laggy)?.let(::add)
                spiderFailure(delta, environment, state, laggy)?.let(::add)
                if (EnvironmentTags.LIQUID_SURFACE in tags) {
                    surfaceJesusFailure(frame, delta, state, supporting)?.let(::add)
                } else {
                    state.surfaceHoverStreak = 0
                }
            }
        }
        if (supporting) state.lastGroundSlipperiness = environment.surfaceSlipperiness
        return MovementEvaluation(failures, supporting)
    }

    /**
     * Fast-ladder: rising on a climbable faster than vanilla allows. The legal rise per tick is the
     * larger of the climb speed and the continuation of the current vertical arc (a jump that reaches
     * a ladder keeps its own momentum), so jumping onto or along a ladder can never trip it.
     *
     * @param frame the movement frame.
     * @param delta position delta of the frame.
     * @param environment sampled environment (jump strength, jump boost, gravity).
     * @param state the player's check state (reads airTicks, lastVerticalDelta, positionGapTicks).
     * @param limits optional sink for the computed ceiling (trace output).
     * @return a failure when the rise exceeds the ceiling, else null.
     */
    fun fastLadderFailure(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        limits: MutableMap<String, Double>? = null,
    ): CheckFailure? {
        if (delta.y <= 0.0) return null
        val ticks = state.positionGapTicks.coerceIn(1, 2)
        val arc = possibleRise(environment, state, ticks)
        val ceiling = max(arc, CLIMB_SPEED * ticks) + 0.03 * ticks + state.verticalUncertainty
        limits?.put("climbCeiling", ceiling.rounded())
        val offset = delta.y - ceiling
        if (offset <= 0.0) return null
        return CheckFailure(
            "movement.fastladder.a",
            (offset / FLY_OFFSET_REF).coerceIn(0.2, 4.0),
            mapOf(
                "dy" to delta.y.rounded(),
                "ceiling" to ceiling.rounded(),
                "offset" to offset.rounded(),
                "previousVelocity" to state.lastVerticalDelta.rounded(),
                "airTicks" to state.airTicks,
                "packetTicks" to ticks,
                "clientGround" to frame.onGround,
            ),
        )
    }

    /**
     * High-jump: leaving the ground with a larger impulse than the active jump boost allows (the
     * legal jump arc over the packet's ticks plus a 0.3 margin). Requires a fresh takeoff so apex
     * and descending frames never fire; also evaluated on climbables.
     *
     * @param delta position delta of the frame.
     * @param environment sampled environment (jump strength, jump boost, gravity).
     * @param state the player's check state.
     * @param supportReliable whether the support sample is fresh enough to trust airTicks.
     * @param limits optional sink for the computed limit (trace output).
     * @return a failure when the takeoff impulse is too large, else null.
     */
    fun highJumpFailure(
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        supportReliable: Boolean,
        limits: MutableMap<String, Double>? = null,
    ): CheckFailure? {
        if (!supportReliable || state.airTicks > 1 || state.lastVerticalDelta > 0.02) return null
        val ticks = state.positionGapTicks.coerceIn(1, 2)
        val limit = jumpArc(environment, ticks) + 0.3
        limits?.put("jumpLimit", limit.rounded())
        if (delta.y <= limit) return null
        return CheckFailure(
            "movement.highjump.a",
            1.5,
            mapOf("dy" to delta.y.rounded(), "limit" to limit.rounded(), "airTicks" to state.airTicks, "packetTicks" to ticks),
        )
    }

    // Largest possible rise over [ticks] ticks: a fresh jump on a takeoff tick, else the current arc.
    private fun possibleRise(environment: EnvironmentFrame, state: PlayerState, ticks: Int): Double {
        val takeoff = (state.airTicks <= 1 || state.lastMovement?.onGround == true) && state.lastVerticalDelta <= 0.05
        if (takeoff) return jumpArc(environment, ticks)
        var vertical = verticalAfterTick(state.lastVerticalDelta, environment)
        var rise = 0.0
        repeat(ticks) {
            rise += vertical
            vertical = verticalAfterTick(vertical, environment)
        }
        return rise
    }

    // Rise of a fresh jump (jump strength + jump boost) over [ticks] ticks.
    private fun jumpArc(environment: EnvironmentFrame, ticks: Int): Double {
        val jumpBoost = if (environment.jumpAmplifier >= 0) 0.1 * (environment.jumpAmplifier + 1) else 0.0
        var vertical = max(environment.jumpStrength, 0.42) + jumpBoost
        var rise = 0.0
        repeat(ticks) {
            rise += vertical
            vertical = verticalAfterTick(vertical, environment)
        }
        return rise
    }

    /** Deterministic protocol check: non-finite or out-of-range position/rotation values. */
    fun badPacketFailure(frame: MovementFrame): CheckFailure? {
        val invalidPosition = frame.positionChanged && (
            !frame.position.x.isFinite() || !frame.position.y.isFinite() ||
            !frame.position.z.isFinite() || abs(frame.position.x) > 30_000_000 || abs(frame.position.z) > 30_000_000 ||
            abs(frame.position.y) > 30_000_000
        )
        val invalidRotation = frame.rotationChanged && (!frame.yaw.isFinite() || !frame.pitch.isFinite() || abs(frame.pitch) > 90.0001f)
        if (!invalidPosition && !invalidRotation) return null
        return CheckFailure(
            "protocol.badpackets.a",
            3.0,
            mapOf("reason" to if (invalidPosition) "invalid-position" else "invalid-rotation", "pitch" to frame.pitch),
            deterministic = true,
        )
    }

    /** Anti-knockback check: the client absorbed far less velocity than the server sent. */
    fun velocityFailure(state: PlayerState, environment: EnvironmentFrame): CheckFailure? {
        val expected = state.pendingVelocity ?: return null
        if (state.clientTick - state.pendingVelocityTick < 6) return null
        state.pendingVelocity = null
        if (environment.exemptEnvironment || environment.colliding) return null
        val expectedHorizontal = sqrt(expected.horizontalLengthSquared())
        val horizontalRatio = if (expectedHorizontal > 0.12) state.velocityObservedHorizontal / expectedHorizontal else 1.0
        val verticalRatio = if (expected.y > 0.12) state.velocityObservedVertical.coerceAtLeast(0.0) / expected.y else 1.0
        if (horizontalRatio >= 0.20 && verticalRatio >= 0.20) return null
        return CheckFailure(
            "movement.velocity.a",
            1.0,
            mapOf(
                "expectedHorizontal" to expectedHorizontal.rounded(),
                "observedHorizontal" to state.velocityObservedHorizontal.rounded(),
                "horizontalRatio" to horizontalRatio.rounded(),
                "expectedY" to expected.y.rounded(),
                "observedY" to state.velocityObservedVertical.rounded(),
                "verticalRatio" to verticalRatio.rounded(),
            ),
        )
    }

    /**
     * Updates the horizontal prediction after an accepted (legal) horizontal move.
     *
     * The carried velocity is the observed one, but never more than the physically possible one:
     * feeding an over-fast (yet still tolerated) move back into the prediction let a constant speed
     * cheat raise its own budget every tick — the tolerance compounded up to almost twice the vanilla
     * sprint speed on flat ground.
     */
    fun acceptHorizontal(delta: Vec3, environment: EnvironmentFrame, state: PlayerState, supporting: Boolean) {
        val ticks = state.positionGapTicks.coerceAtLeast(1)
        val horizontal = sqrt(delta.horizontalLengthSquared()) / ticks
        val observedNext = horizontal * carriedFriction(environment, state, supporting)
        val legalNext = horizontalProjection(environment, state, ticks.coerceAtMost(2), supporting).nextVelocity
        // A multi-tick packet only shows the average speed, so carry the projected end velocity.
        state.predictedHorizontal = (if (ticks > 1) legalNext else min(observedNext, legalNext)).coerceIn(0.0, 1.2)
    }

    /** Re-projects the horizontal prediction after a rejected move (keeps the model honest). */
    fun rejectHorizontal(environment: EnvironmentFrame, state: PlayerState, supporting: Boolean) {
        val ticks = state.positionGapTicks.coerceIn(1, 2)
        state.predictedHorizontal = horizontalProjection(environment, state, ticks, supporting).nextVelocity
    }

    /** Updates the vertical prediction after an accepted vertical move. */
    fun acceptVertical(delta: Vec3, environment: EnvironmentFrame, state: PlayerState, supporting: Boolean) {
        val ticks = state.positionGapTicks.coerceAtLeast(1)
        val vertical = delta.y / ticks
        state.predictedVertical = if (supporting) 0.0 else verticalAfterTick(vertical, environment)
    }

    /** Advances the vertical prediction after a rejected move. */
    fun rejectVertical(environment: EnvironmentFrame, state: PlayerState, supporting: Boolean) {
        state.predictedVertical = if (supporting) {
            0.0
        } else {
            verticalAfterTick(state.predictedVertical, environment)
        }
    }

    /** NoFall check for ground-only packets: claimed ground with no supporting collision. */
    fun groundSpoofFailure(
        onGround: Boolean,
        position: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        laggy: Boolean = false,
    ): CheckFailure? {
        val minAirTicks = if (laggy) LAG_NOFALL_AIRTICKS else 3
        if (!onGround || supports(position, environment) || state.airTicks < minAirTicks) return null
        return CheckFailure(
            "movement.nofall.a",
            1.0,
            mapOf(
                "dy" to state.lastVerticalDelta.rounded(),
                "airTicks" to state.airTicks,
                "clientGround" to true,
                "support" to false,
                "packet" to "ground-only",
            ),
        )
    }

    /**
     * Elytra glide-hack check ("no-fall"/hover flight while wearing an elytra).
     *
     * Vanilla elytra flight is never gravity-free. Every tick while gliding, the entity's vertical
     * motion still has gravity (`EnvironmentFrame.gravity`, 0.08 blocks/tick²) subtracted from it
     * before the glide's own lift/drag terms are applied (vanilla's `LivingEntity#travel` elytra
     * branch) — a firework rocket boost only ADDS a short burst of upward acceleration on top of that
     * pull for the few ticks the rocket burns, it does not remove the pull itself. The practical
     * result: a legitimate glider's vertical delta always dips back to flat-or-falling within a tick
     * or two once a boost's burn ends, even when chaining rockets to climb — there is no way to keep
     * `delta.y` non-negative on every single tick, indefinitely, without something removing gravity
     * outright. A hacked client that fakes "still gliding, immune to gravity" is exactly that: it
     * reports a vertical delta that stays flat-or-rising tick after tick for as long as the hack is
     * active, with none of the brief negative dips a real rocket-chaining player cannot avoid.
     *
     * The streak threshold below is deliberately generous specifically so it survives normal
     * boost-chaining and network jitter; per the false-positive-bar directive this trades detection
     * speed for near-certainty. It is frame-based (consecutive qualifying MOVEMENT PACKETS), matching
     * every other streak check in this file (e.g. [CheckEngine]'s sprint-backwards streak) rather than
     * wall-clock time, so it is insensitive to a client's exact packet rate.
     *
     * Deliberately does not touch [PlayerState.predictedHorizontal]/[PlayerState.predictedVertical]
     * (the shared dry-land motion predictor): this check runs independently of the fly/speed checks
     * (see `CheckEngine.processMovement`), so it must not perturb their state for the frame after the
     * player lands.
     *
     * @param delta this frame's position delta.
     * @param state the player's check state (reads/writes [PlayerState.elytraLevelStreak]).
     * @return a failure once the non-descending streak crosses the threshold, else `null`.
     */
    fun elytraFailure(delta: Vec3, state: PlayerState): CheckFailure? {
        if (delta.y < -ELYTRA_DESCENT_EPSILON) {
            state.elytraLevelStreak = 0
            return null
        }
        state.elytraLevelStreak++
        if (state.elytraLevelStreak < ELYTRA_STREAK_FRAMES) {
            return null
        }
        return CheckFailure(
            "movement.elytrafly.a",
            1.0,
            mapOf("dy" to delta.y.rounded(), "streak" to state.elytraLevelStreak),
        )
    }

    /** Seeds the motion model from the observed frame (exempt frames, teleport, respawn). */
    fun baseline(delta: Vec3, environment: EnvironmentFrame, state: PlayerState) {
        // Seed the horizontal predictor from the OBSERVED momentum: zeroing it (as before) made the
        // first frame after an exemption — knockback (every PvP hit!), elytra landing, teleport
        // grace — compare real, legitimate momentum against a stand-still budget and speed-flag it.
        val friction = if (environment.supportingCollision) groundFriction(environment) else 0.91
        state.predictedHorizontal = (sqrt(delta.horizontalLengthSquared()) * friction).coerceIn(0.0, 1.2)
        state.predictedVertical = if (environment.supportingCollision) 0.0 else verticalAfterTick(delta.y.coerceIn(-0.6, 0.6), environment)
    }

    /** Decays the predictions for a tick without a position change. */
    fun idle(environment: EnvironmentFrame, state: PlayerState) {
        val friction = if (environment.supportingCollision) groundFriction(environment) else 0.91
        state.predictedHorizontal *= friction
        state.predictedVertical = if (environment.supportingCollision) 0.0 else verticalAfterTick(state.predictedVertical, environment)
    }

    private fun flyFailure(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        profile: VersionProfile,
        state: PlayerState,
        ticks: Int,
        supporting: Boolean,
        laggy: Boolean,
        limits: MutableMap<String, Double>?,
    ): CheckFailure? {
        if (supporting) return null
        val jumpBoost = if (environment.jumpAmplifier >= 0) 0.1 * (environment.jumpAmplifier + 1) else 0.0
        // A jump is also accepted when the previous frame CLAIMED ground: phantom air ticks (support
        // sample lagging a fast player) must not turn a normal jump into a huge fly offset. Lying
        // about ground buys only a jump-sized ceiling and is flagged independently by nofall.
        val claimedGround = state.lastMovement?.onGround == true
        val takeoff = (state.airTicks <= 1 || claimedGround) && state.lastVerticalDelta <= 0.05 && delta.y > 0.0
        var vertical = if (takeoff) {
            max(environment.jumpStrength, profile.jumpVelocity) + jumpBoost
        } else {
            verticalAfterTick(state.lastVerticalDelta, environment)
        }
        var maximumRise = 0.0
        repeat(ticks) {
            maximumRise += vertical
            vertical = verticalAfterTick(vertical, environment)
        }
        val stepAllowance = if (state.airTicks == 0) environment.stepHeight.coerceIn(0.0, 1.5) else 0.0
        // Physics uncertainty of the possible vertical interval: sampling jitter, step, lag widening,
        // plus the decaying knockback residual. The offset is how far the observed rise is OUTSIDE the
        // largest physically possible rise — 0 means definitely legal (no guessed flat tolerance).
        val tolerance = 0.035 * ticks + stepAllowance + (if (laggy) 0.05 * ticks else 0.0)
        val ceiling = maximumRise + tolerance + state.verticalUncertainty
        limits?.put("flyCeiling", ceiling.rounded())
        val offset = delta.y - ceiling
        val expectedFall = maximumRise < -0.08 && delta.y > maximumRise + 0.09
        val hovering = state.airTicks > 7 && expectedFall && abs(delta.y) < 0.03 && state.verticalUncertainty <= 0.0
        if (offset <= 0.0 && !hovering) return null
        // Weight is the offset itself in FP-safe units: a borderline offset (a few cm over possible)
        // barely accrues and decays away; a clear fly accrues fast. Deterministic, self-calibrating.
        val weight = if (offset > 0.0) (offset / FLY_OFFSET_REF).coerceIn(0.1, 8.0) else 1.0
        return CheckFailure(
            "movement.fly.a",
            weight,
            mapOf(
                "dy" to delta.y.rounded(),
                "maximum" to maximumRise.rounded(),
                "ceiling" to ceiling.rounded(),
                "offset" to offset.coerceAtLeast(0.0).rounded(),
                "knockback" to state.verticalUncertainty.rounded(),
                "previousVelocity" to state.lastVerticalDelta.rounded(),
                "airTicks" to state.airTicks,
                "packetTicks" to ticks,
            ),
        )
    }

    private fun speedFailure(
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        ticks: Int,
        supporting: Boolean,
        laggy: Boolean,
        limits: MutableMap<String, Double>?,
    ): CheckFailure? {
        val projection = horizontalProjection(environment, state, ticks, supporting)
        // The possible-horizontal interval: the projected budget (already carries physics tolerance),
        // widened under lag, plus the decaying knockback residual. The offset is how far the observed
        // horizontal is OUTSIDE the largest physically possible move.
        val limit = projection.limit * (if (laggy) 1.15 else 1.0) + state.horizontalUncertainty
        val horizontal = sqrt(delta.horizontalLengthSquared())
        limits?.put("speedLimit", limit.rounded())
        val offset = horizontal - limit
        if (offset <= 0.0) return sustainedSpeedFailure(horizontal, projection, environment, state, ticks, laggy)
        resetSpeedWindow(state)
        state.lastSpeedFailureTick = state.clientTick
        // Weight is the offset in FP-safe units — a hair over the limit barely accrues and decays
        // away; a real speed cheat accrues fast. Repeated offsets accumulate in the VL naturally, so
        // no separate streak bonus (which could amplify a borderline legit player) is needed.
        val weight = (offset / SPEED_OFFSET_REF).coerceIn(0.1, 8.0)
        return CheckFailure(
            "movement.speed.a",
            weight,
            mapOf(
                "horizontal" to horizontal.rounded(),
                "limit" to limit.rounded(),
                "offset" to offset.rounded(),
                "knockback" to state.horizontalUncertainty.rounded(),
                "acceleration" to projection.acceleration.rounded(),
                "friction" to projection.friction.rounded(),
                "jumpImpulse" to projection.jumpImpulse.rounded(),
                "takeoffAllowance" to projection.takeoffAllowance.rounded(),
                "sprinting" to state.sprinting,
                "surface" to environment.surface,
                "packetTicks" to ticks,
            ),
        )
    }

    /**
     * The sustained part of the speed check: a small constant speed boost (10–20 %) stays inside the
     * per-tick tolerance on every single frame, but not on average. Over [SPEED_WINDOW] clean frames
     * (no lag, knockback, environment tag or collision, single-tick packets, really moving) the observed
     * horizontal distance is compared with the vanilla-possible one without any tolerance; legit movement
     * stays at or below it (the model is an upper bound), a boost sits clearly above it.
     */
    private fun sustainedSpeedFailure(
        horizontal: Double,
        projection: HorizontalProjection,
        environment: EnvironmentFrame,
        state: PlayerState,
        ticks: Int,
        laggy: Boolean,
    ): CheckFailure? {
        val clean = !laggy && ticks == 1 && state.horizontalUncertainty <= 0.0 && environment.environmentTags.isEmpty() &&
            !environment.colliding && horizontal >= SPEED_WINDOW_MIN_MOVE
        if (!clean) {
            resetSpeedWindow(state)
            return null
        }
        state.speedWindowFrames++
        state.speedWindowObserved += horizontal
        state.speedWindowVanilla += projection.vanilla
        if (state.speedWindowFrames < SPEED_WINDOW) return null
        val observed = state.speedWindowObserved
        val vanilla = state.speedWindowVanilla.coerceAtLeast(1e-6)
        val frames = state.speedWindowFrames
        resetSpeedWindow(state)
        val ratio = observed / vanilla
        if (ratio <= SPEED_WINDOW_RATIO || observed - vanilla < SPEED_WINDOW_MIN_EXCESS) return null
        state.lastSpeedFailureTick = state.clientTick
        return CheckFailure(
            "movement.speed.a",
            ((ratio - 1.0) / SPEED_WINDOW_RATIO_REF).coerceIn(0.5, 4.0),
            mapOf(
                "mode" to "sustained",
                "ratio" to ratio.rounded(),
                "observed" to observed.rounded(),
                "vanilla" to vanilla.rounded(),
                "frames" to frames,
                "sprinting" to state.sprinting,
                "surface" to environment.surface,
            ),
        )
    }

    private fun resetSpeedWindow(state: PlayerState) {
        state.speedWindowFrames = 0
        state.speedWindowObserved = 0.0
        state.speedWindowVanilla = 0.0
    }

    private fun horizontalProjection(
        environment: EnvironmentFrame,
        state: PlayerState,
        ticks: Int,
        supporting: Boolean,
    ): HorizontalProjection {
        // Vanilla takes the acceleration from the ground state at the START of the tick: the tick that walks
        // off an edge or jumps still accelerates like on ground (walking edge-offs and jumps flagged before).
        val startedGrounded = startedGrounded(state)
        // Sprint takeoff: the tick left the ground (jump, or a sprint off an edge – the impulse then only
        // widens the budget). Same phantom-air defense as the fly takeoff: a client-claimed ground frame
        // also qualifies, otherwise a normal re-jump after a lagging support sample speed-flags.
        // Ground-claim abuse is caught by nofall, not here.
        val takingOff = !supporting && state.sprinting && (state.airTicks == 0 || state.lastMovement?.onGround == true)
        val grounded = supporting || startedGrounded || takingOff
        val friction = carriedFriction(environment, state, supporting)
        val speedEffect = if (environment.speedAmplifier >= 0) 1.0 + 0.2 * (environment.speedAmplifier + 1) else 1.0
        val sprint = if (state.sprinting) 1.3 else 1.0
        val walkScale = (environment.walkSpeed / 0.2).coerceIn(0.1, 10.0)
        val baseAttribute = max(environment.movementSpeed, 0.1 * speedEffect * sprint * walkScale)
        // Vanilla ground acceleration: speed * 0.216 / blockFriction³ with the RAW block friction (0.6
        // on normal blocks → factor 1.0). Cubing the already 0.91-scaled friction overestimated every
        // ground acceleration by a third.
        val acceleration = if (grounded) {
            // Takeoff frames sample the block below the air they are in; the block left behind decides.
            val slipperiness = if (supporting) environment.surfaceSlipperiness else min(environment.surfaceSlipperiness, state.lastGroundSlipperiness)
            baseAttribute * (0.21600002 / slipperiness.coerceIn(0.1, 0.999).pow(3.0))
        } else {
            if (state.sprinting) 0.026 else 0.02
        }
        var velocity = if (takingOff) {
            max(state.predictedHorizontal, state.lastHorizontalDelta)
        } else {
            state.predictedHorizontal
        }.coerceIn(0.0, 1.2)
        var allowed = 0.0
        val jumpImpulse = if (takingOff) 0.2 else 0.0
        repeat(ticks) { index ->
            velocity += acceleration + if (index == 0) jumpImpulse else 0.0
            allowed += velocity
            velocity *= friction
        }
        // The takeoff tick now gets its real (ground) acceleration, which the former 0.12 allowance stood in for.
        val takeoffAllowance = 0.0
        val sprintInertiaAllowance = if (grounded && state.sprinting) 0.04 else 0.0
        val tolerance = 0.035 * ticks + takeoffAllowance + sprintInertiaAllowance + if (environment.colliding) 0.1 else 0.0
        return HorizontalProjection(
            allowed + tolerance,
            allowed,
            velocity.coerceIn(0.0, 1.2),
            acceleration,
            friction,
            jumpImpulse,
            takeoffAllowance,
        )
    }

    private fun noFallFailure(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        supporting: Boolean,
        laggy: Boolean,
    ): CheckFailure? {
        // Under lag the sampled support state is unreliable, so require sustained airborne evidence
        // before flagging ground-spoof (prevents nofall FP cascades on legit grounded players).
        val minAirTicks = if (laggy) LAG_NOFALL_AIRTICKS else 3
        if (!frame.onGround || supporting || state.airTicks < minAirTicks || delta.y >= -0.03) return null
        return CheckFailure(
            "movement.nofall.a",
            1.0,
            mapOf(
                "dy" to delta.y.rounded(),
                "airTicks" to state.airTicks,
                "clientGround" to true,
                "support" to false,
            ),
        )
    }

    private fun phaseFailure(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        laggy: Boolean,
    ): CheckFailure? {
        // Only real physics modifiers skip the check; context tags (low TPS, liquid surface) do not.
        if (sqrt(delta.horizontalLengthSquared()) < 0.18 || EnvironmentTags.physicsModified(environment.environmentTags)) return null
        val previous = state.lastMovement ?: return null
        val height = environment.entityBox.maxY - environment.entityBox.minY
        val width = environment.entityBox.maxX - environment.entityBox.minX
        // Under low TPS block updates reach the sampler late, so the body must enter a box deeper
        // before it counts (a just-broken block can linger one sample longer).
        val shrink = if (laggy) 0.1 else 0.03

        /** Shrunken body box at [position] for the collision-entry test. */
        fun body(position: Vec3) = Box(
            position.x - width / 2 + shrink, position.y + 0.05, position.z - width / 2 + shrink,
            position.x + width / 2 - shrink, position.y + height - 0.05, position.z + width / 2 - shrink,
        )
        val entered = environment.collisionBoxes.any(body(frame.position)::intersects)
        val wasInside = environment.collisionBoxes.any(body(previous.position)::intersects)
        if (!entered || wasInside) return null
        return CheckFailure("movement.phase.a", 1.0, mapOf("horizontal" to sqrt(delta.horizontalLengthSquared()).rounded(), "enteredCollision" to true))
    }

    private fun stepFailure(delta: Vec3, environment: EnvironmentFrame, state: PlayerState, laggy: Boolean): CheckFailure? {
        // Jump boost raises the legal takeoff impulse by 0.1 per amplifier level; without the
        // allowance a Jump Boost III+ player step-flagged on every jump. Low TPS widens the margin.
        val jumpBoost = if (environment.jumpAmplifier >= 0) 0.1 * (environment.jumpAmplifier + 1) else 0.0
        val margin = if (laggy) 0.1 else 0.0
        // A step starts from the ground without upward momentum; a frame that continues a rising arc (the
        // second tick of a slime bounce rises ~0.94) is the fly check's business, not a step.
        if (delta.y <= environment.stepHeight + 0.12 + margin || delta.y <= 0.72 + jumpBoost + margin || state.airTicks > 1 ||
            state.lastVerticalDelta > 0.05 || EnvironmentTags.physicsModified(environment.environmentTags)
        ) return null
        return CheckFailure("movement.step.a", 1.0, mapOf("dy" to delta.y.rounded(), "stepHeight" to environment.stepHeight.rounded(), "laggy" to laggy))
    }

    private fun spiderFailure(delta: Vec3, environment: EnvironmentFrame, state: PlayerState, laggy: Boolean): CheckFailure? {
        // Under low TPS the support sample lags further behind, so demand twice the airborne evidence.
        val minAirTicks = if (laggy) 8 else 4
        if (!environment.colliding || state.airTicks < minAirTicks || delta.y !in 0.14..0.60 ||
            EnvironmentTags.physicsModified(environment.environmentTags)
        ) return null
        return CheckFailure("movement.spider.a", 1.0, mapOf("dy" to delta.y.rounded(), "airTicks" to state.airTicks, "colliding" to true, "laggy" to laggy))
    }

    private fun jesusFailure(frame: MovementFrame, delta: Vec3, state: PlayerState, supporting: Boolean): CheckFailure? {
        if (!frame.onGround || supporting || state.airTicks < 4 || abs(delta.y) > 0.04) return null
        return CheckFailure("movement.jesus.a", 1.0, mapOf("dy" to delta.y.rounded(), "airTicks" to state.airTicks, "clientGround" to true))
    }

    /**
     * Jesus on the surface: the feet are in air right above a liquid without any supporting block, and
     * the client either claims ground or keeps hovering level — vanilla gravity pulls a player there
     * into the liquid within a tick or two.
     */
    private fun surfaceJesusFailure(frame: MovementFrame, delta: Vec3, state: PlayerState, supporting: Boolean): CheckFailure? {
        val level = !supporting && abs(delta.y) <= 0.04
        state.surfaceHoverStreak = if (level) state.surfaceHoverStreak + 1 else 0
        if (supporting || state.airTicks < 4) return null
        val claimedGround = frame.onGround && abs(delta.y) <= 0.04
        if (!claimedGround && state.surfaceHoverStreak < JESUS_HOVER_FRAMES) return null
        return CheckFailure(
            "movement.jesus.a",
            1.0,
            mapOf(
                "dy" to delta.y.rounded(),
                "airTicks" to state.airTicks,
                "clientGround" to frame.onGround,
                "hoverFrames" to state.surfaceHoverStreak,
                "surface" to true,
            ),
        )
    }

    /** True when a collision box supports the player's feet at [position]. */
    fun supports(position: Vec3, environment: EnvironmentFrame): Boolean {
        val width = environment.entityBox.maxX - environment.entityBox.minX
        // Vanilla keeps a player grounded on an arbitrarily small box overlap, so the feet box must
        // shrink by no more than a hair: the previous 2cm shrink read real edge-standing as airborne
        // (hover-fly/nofall FPs). The hair is still needed so exact wall contact (coordinates resolved
        // onto the face by the client) does not count as support.
        val half = width / 2.0 - 0.001
        val feet = Box(
            position.x - half,
            position.y - 0.08,
            position.z - half,
            position.x + half,
            position.y + 0.03,
            position.z + half,
        )
        return environment.collisionBoxes.any(feet::intersects)
    }

    private fun groundFriction(environment: EnvironmentFrame) = (environment.surfaceSlipperiness * 0.91).coerceIn(0.1, 0.99)

    // Vanilla applies the friction of the block the player stood on at the START of the tick: the tick
    // that lands still carries air friction (0.91) into the first ground tick, the takeoff tick (jump,
    // edge-off) still ground friction. Using ground friction on landing under-predicted the next ground
    // tick; air friction after a takeoff let the next air ticks carry almost twice the jump speed.
    private fun carriedFriction(environment: EnvironmentFrame, state: PlayerState, supporting: Boolean) = when {
        !startedGrounded(state) -> 0.91
        supporting -> groundFriction(environment)
        else -> max(groundFriction(environment), (state.lastGroundSlipperiness * 0.91).coerceIn(0.1, 0.99))
    }

    /**
     * The ground state at the start of the current tick, i.e. after the previous move: the previous frame
     * was supported, or – right at an edge, where the support sample of its end position already misses the
     * block although the move ended on it – the client claimed ground one frame ago. A spoofed claim buys
     * at most one tick (ground spoofing itself is nofall's job).
     */
    private fun startedGrounded(state: PlayerState): Boolean =
        state.airTicks == 0 || (state.airTicks <= 1 && state.lastMovement?.onGround == true)

    private fun verticalAfterTick(vertical: Double, environment: EnvironmentFrame): Double {
        val gravity = environment.gravity.coerceIn(0.01, 0.2)
        return (vertical - gravity) * 0.98
    }
}

private fun Double.rounded() = round(this * 10000.0) / 10000.0
