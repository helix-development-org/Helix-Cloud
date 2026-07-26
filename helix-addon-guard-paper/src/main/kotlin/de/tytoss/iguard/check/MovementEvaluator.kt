package de.tytoss.iguard.check

import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.EnvironmentFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.Vec3
import de.tytoss.iguard.profile.VersionProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

internal data class MovementEvaluation(val failures: List<CheckFailure>, val supporting: Boolean)

// Airborne ticks required before flagging ground-spoof/nofall while the server is lagging (sampled
// support is unreliable under lag). Well above vanilla fall onset so real nofall still trips.
private const val LAG_NOFALL_AIRTICKS = 12

private data class HorizontalProjection(
    val limit: Double,
    val nextVelocity: Double,
    val acceleration: Double,
    val friction: Double,
    val jumpImpulse: Double,
    val takeoffAllowance: Double
)

internal class MovementEvaluator {
    /** Runs the movement checks for one frame and returns the failures + support flag. */
    fun evaluate(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        profile: VersionProfile,
        state: PlayerState,
        laggy: Boolean = false
    ): MovementEvaluation {
        val ticks = state.positionGapTicks.coerceIn(1, 2)
        val supporting = supports(frame.position, environment)
        val failures = buildList {
            if ("liquid" in environment.environmentTags) {
                jesusFailure(frame, delta, state, supporting)?.let(::add)
            } else {
                flyFailure(frame, delta, environment, profile, state, ticks, supporting, laggy)?.let(::add)
                speedFailure(delta, environment, state, ticks, supporting, laggy)?.let(::add)
                noFallFailure(frame, delta, environment, state, supporting, laggy)?.let(::add)
                phaseFailure(frame, delta, environment, state)?.let(::add)
                stepFailure(delta, environment, state)?.let(::add)
                spiderFailure(delta, environment, state)?.let(::add)
            }
        }
        return MovementEvaluation(failures, supporting)
    }

    /** Deterministic protocol check: non-finite or out-of-range position/rotation values. */
    fun badPacketFailure(frame: MovementFrame): CheckFailure? {
        val invalidPosition = frame.positionChanged && (!frame.position.x.isFinite() || !frame.position.y.isFinite() ||
            !frame.position.z.isFinite() || abs(frame.position.x) > 30_000_000 || abs(frame.position.z) > 30_000_000 ||
            abs(frame.position.y) > 30_000_000)
        val invalidRotation = frame.rotationChanged && (!frame.yaw.isFinite() || !frame.pitch.isFinite() || abs(frame.pitch) > 90.0001f)
        if (!invalidPosition && !invalidRotation) return null
        return CheckFailure(
            "protocol.badpackets.a",
            3.0,
            mapOf("reason" to if (invalidPosition) "invalid-position" else "invalid-rotation", "pitch" to frame.pitch),
            deterministic = true
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
                "verticalRatio" to verticalRatio.rounded()
            )
        )
    }

    /** Updates the horizontal prediction after an accepted (legal) horizontal move. */
    fun acceptHorizontal(delta: Vec3, environment: EnvironmentFrame, state: PlayerState, supporting: Boolean) {
        val ticks = state.positionGapTicks.coerceAtLeast(1)
        val horizontal = sqrt(delta.horizontalLengthSquared()) / ticks
        val friction = if (supporting) groundFriction(environment) else 0.91
        val observedNext = horizontal * friction
        val projectedNext = if (ticks > 1) {
            horizontalProjection(environment, state, ticks.coerceAtMost(2), supporting).nextVelocity
        } else {
            0.0
        }
        state.predictedHorizontal = max(observedNext, projectedNext).coerceIn(0.0, 1.2)
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
        laggy: Boolean = false
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
                "packet" to "ground-only"
            )
        )
    }

    /** Seeds the motion model after a reset (first frame, teleport, respawn). */
    fun baseline(delta: Vec3, environment: EnvironmentFrame, state: PlayerState) {
        state.predictedHorizontal = 0.0
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
        laggy: Boolean
    ): CheckFailure? {
        if (supporting) return null
        val jumpBoost = if (environment.jumpAmplifier >= 0) 0.1 * (environment.jumpAmplifier + 1) else 0.0
        val takeoff = state.airTicks <= 1 && state.lastVerticalDelta <= 0.05 && delta.y > 0.0
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
        // Under server lag the sampled environment lags the client; widen the margin so a legit
        // player is not flagged. Detection stays active (unlike the old blanket low-tps exemption).
        val tolerance = 0.035 * ticks + stepAllowance + if (laggy) 0.05 * ticks else 0.0
        val impossibleRise = delta.y > maximumRise + tolerance
        val expectedFall = maximumRise < -0.08 && delta.y > maximumRise + 0.09
        val hovering = state.airTicks > 7 && expectedFall && abs(delta.y) < 0.03
        if (!impossibleRise && !hovering) return null
        return CheckFailure(
            "movement.fly.a",
            if (impossibleRise) 1.25 else 1.0,
            mapOf(
                "dy" to delta.y.rounded(),
                "maximum" to maximumRise.rounded(),
                "previousVelocity" to state.lastVerticalDelta.rounded(),
                "airTicks" to state.airTicks,
                "packetTicks" to ticks
            )
        )
    }

    private fun speedFailure(
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        ticks: Int,
        supporting: Boolean,
        laggy: Boolean
    ): CheckFailure? {
        val projection = horizontalProjection(environment, state, ticks, supporting)
        // Under lag, widen the horizontal budget rather than skipping the check entirely.
        val limit = projection.limit * if (laggy) 1.15 else 1.0
        val horizontal = sqrt(delta.horizontalLengthSquared())
        if (horizontal <= limit) return null
        state.speedFailureStreak = if (state.clientTick - state.lastSpeedFailureTick <= 30) {
            state.speedFailureStreak + 1
        } else {
            1
        }
        state.lastSpeedFailureTick = state.clientTick
        val baseWeight = (horizontal / limit.coerceAtLeast(0.01)).coerceIn(1.0, 2.5)
        val repeatBonus = (state.speedFailureStreak - 1) * 0.75
        return CheckFailure(
            "movement.speed.a",
            (baseWeight + repeatBonus).coerceAtMost(6.0),
            mapOf(
                "horizontal" to horizontal.rounded(),
                "limit" to limit.rounded(),
                "acceleration" to projection.acceleration.rounded(),
                "friction" to projection.friction.rounded(),
                "jumpImpulse" to projection.jumpImpulse.rounded(),
                "takeoffAllowance" to projection.takeoffAllowance.rounded(),
                "repeat" to state.speedFailureStreak,
                "sprinting" to state.sprinting,
                "surface" to environment.surface,
                "packetTicks" to ticks
            )
        )
    }

    private fun horizontalProjection(
        environment: EnvironmentFrame,
        state: PlayerState,
        ticks: Int,
        supporting: Boolean
    ): HorizontalProjection {
        val grounded = supporting
        val friction = if (grounded) groundFriction(environment) else 0.91
        val speedEffect = if (environment.speedAmplifier >= 0) 1.0 + 0.2 * (environment.speedAmplifier + 1) else 1.0
        val sprint = if (state.sprinting) 1.3 else 1.0
        val walkScale = (environment.walkSpeed / 0.2).coerceIn(0.1, 10.0)
        val baseAttribute = max(environment.movementSpeed, 0.1 * speedEffect * sprint * walkScale)
        val acceleration = if (grounded) {
            baseAttribute * (0.21600002 / friction.pow(3.0))
        } else {
            if (state.sprinting) 0.026 else 0.02
        }
        val takingOff = !grounded && state.airTicks == 0 && state.sprinting
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
        val takeoffAllowance = if (takingOff) 0.12 else 0.0
        val sprintInertiaAllowance = if (grounded && state.sprinting) 0.04 else 0.0
        val tolerance = 0.035 * ticks + takeoffAllowance + sprintInertiaAllowance + if (environment.colliding) 0.1 else 0.0
        return HorizontalProjection(
            allowed + tolerance,
            velocity.coerceIn(0.0, 1.2),
            acceleration,
            friction,
            jumpImpulse,
            takeoffAllowance
        )
    }

    private fun noFallFailure(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState,
        supporting: Boolean,
        laggy: Boolean
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
                "support" to false
            )
        )
    }

    private fun phaseFailure(
        frame: MovementFrame,
        delta: Vec3,
        environment: EnvironmentFrame,
        state: PlayerState
    ): CheckFailure? {
        if (sqrt(delta.horizontalLengthSquared()) < 0.18 || environment.environmentTags.isNotEmpty()) return null
        val previous = state.lastMovement ?: return null
        val height = environment.entityBox.maxY - environment.entityBox.minY
        val width = environment.entityBox.maxX - environment.entityBox.minX
        /** Shrunken body box at [position] for the collision-entry test. */
        fun body(position: Vec3) = Box(
            position.x - width / 2 + 0.03, position.y + 0.05, position.z - width / 2 + 0.03,
            position.x + width / 2 - 0.03, position.y + height - 0.05, position.z + width / 2 - 0.03
        )
        val entered = environment.collisionBoxes.any(body(frame.position)::intersects)
        val wasInside = environment.collisionBoxes.any(body(previous.position)::intersects)
        if (!entered || wasInside) return null
        return CheckFailure("movement.phase.a", 1.0, mapOf("horizontal" to sqrt(delta.horizontalLengthSquared()).rounded(), "enteredCollision" to true))
    }

    private fun stepFailure(delta: Vec3, environment: EnvironmentFrame, state: PlayerState): CheckFailure? {
        if (delta.y <= environment.stepHeight + 0.12 || delta.y <= 0.72 || state.airTicks > 1 || environment.environmentTags.isNotEmpty()) return null
        return CheckFailure("movement.step.a", 1.0, mapOf("dy" to delta.y.rounded(), "stepHeight" to environment.stepHeight.rounded()))
    }

    private fun spiderFailure(delta: Vec3, environment: EnvironmentFrame, state: PlayerState): CheckFailure? {
        if (!environment.colliding || state.airTicks < 4 || delta.y !in 0.14..0.60 || environment.environmentTags.isNotEmpty()) return null
        return CheckFailure("movement.spider.a", 1.0, mapOf("dy" to delta.y.rounded(), "airTicks" to state.airTicks, "colliding" to true))
    }

    private fun jesusFailure(frame: MovementFrame, delta: Vec3, state: PlayerState, supporting: Boolean): CheckFailure? {
        if (!frame.onGround || supporting || state.airTicks < 4 || abs(delta.y) > 0.04) return null
        return CheckFailure("movement.jesus.a", 1.0, mapOf("dy" to delta.y.rounded(), "airTicks" to state.airTicks, "clientGround" to true))
    }

    /** True when a collision box supports the player's feet at [position]. */
    fun supports(position: Vec3, environment: EnvironmentFrame): Boolean {
        val width = environment.entityBox.maxX - environment.entityBox.minX
        val half = width / 2.0 - 0.02
        val feet = Box(
            position.x - half,
            position.y - 0.08,
            position.z - half,
            position.x + half,
            position.y + 0.03,
            position.z + half
        )
        return environment.collisionBoxes.any(feet::intersects)
    }

    private fun groundFriction(environment: EnvironmentFrame) = (environment.surfaceSlipperiness * 0.91).coerceIn(0.1, 0.99)

    private fun verticalAfterTick(vertical: Double, environment: EnvironmentFrame): Double {
        val gravity = environment.gravity.coerceIn(0.01, 0.2)
        return (vertical - gravity) * 0.98
    }
}

private fun Double.rounded() = round(this * 10000.0) / 10000.0
