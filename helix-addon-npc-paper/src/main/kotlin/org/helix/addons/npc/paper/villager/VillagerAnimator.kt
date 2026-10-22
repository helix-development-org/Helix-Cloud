package org.helix.addons.npc.paper.villager

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural animation of a villager (design concept §12 "Animationen"), pure and time-driven.
 *
 * Call [frame] with the current time; it returns the per-part [PartAnim]s that [Kinematics.solve] turns into display
 * transformations. Timings follow §12 and `villager-vorlage.js`: idle bob 2 s · 0.35 px, blink every 2.5–6 s for
 * 3 ticks, head follow ±35°, brow raise 0.9 s · +0.9 px (on address and every 4–8 s), speaking mouth/nose at the
 * template's `sin(9t)` rate.
 *
 * @property lidRest lid y-scale of open eyes.
 * @property random randomness source (seedable for tests).
 */
class VillagerAnimator(
    private val lidRest: Float = 0.05f,
    private val random: Random = Random.Default,
) {
    private var nextBlink = 2.0
    private var nextBrow = 4.0
    private var browStart = -10.0
    private var cheerStart = -10.0
    private var sulkStart = -10.0
    private var headYaw = 0.0
    private var headPitch = 0.0

    /** Whether the villager sleeps (head sunk, lids shut). */
    var sleeping: Boolean = false

    /**
     * Raises the brow once (address, surprise, offer).
     *
     * @param now current time in seconds.
     */
    fun raiseBrow(now: Double) {
        browStart = now
    }

    /**
     * Plays the cheer (small hop, brow up, open mouth; victory, purchase, upgrade).
     *
     * @param now current time in seconds.
     */
    fun cheer(now: Double) {
        cheerStart = now
        browStart = now
    }

    /**
     * Plays the sulk (head turned away and down, low brow, narrow mouth; defeat, error).
     *
     * @param now current time in seconds.
     */
    fun sulk(now: Double) {
        sulkStart = now
    }

    /**
     * Computes the pose of one frame.
     *
     * @param now current time in seconds (monotonic).
     * @param look target head angles (yaw, pitch in radians, model space, see [Kinematics.lookAngles]) of the
     *   followed player, or `null` to look around slowly.
     * @param speaking whether a line is being spoken.
     * @return animation per part name.
     */
    fun frame(now: Double, look: Pair<Double, Double>?, speaking: Boolean): Map<String, PartAnim> {
        val anims = HashMap<String, PartAnim>()
        val cheerK = now - cheerStart
        val sulkK = now - sulkStart
        val cheering = cheerK in 0.0..CHEER_SECONDS
        val sulking = sulkK in 0.0..SULK_SECONDS

        // body: idle bob, root: cheer hop
        val bobSpeed = if (sleeping) 0.25 else 0.5
        anims["body"] = PartAnim(offset = Vec3(0.0, abs(sin(now * PI * bobSpeed)) * BOB_PX, 0.0))
        if (cheering && cheerK < HOP_SECONDS) {
            anims["root"] = PartAnim(offset = Vec3(0.0, sin(cheerK / HOP_SECONDS * PI) * HOP_PX, 0.0))
        }

        // head: follow / look around, sulk and sleep overrides, smoothed
        val (targetYaw, targetPitch) = when {
            sleeping -> 0.0 to SLEEP_PITCH
            sulking -> SULK_YAW to SULK_PITCH
            look != null && abs(look.first) <= FOLLOW_MAX_TURN ->
                look.first.coerceIn(-MAX_YAW, MAX_YAW) to look.second.coerceIn(-MAX_PITCH, MAX_PITCH)
            else -> sin(now * 0.45) * LOOK_AROUND_YAW to 0.0
        }
        headYaw += (targetYaw - headYaw) * HEAD_EASE
        headPitch += (targetPitch - headPitch) * HEAD_EASE
        val roll = sin(now * 0.9) * 0.04
        anims["head"] = PartAnim(rotation = Quat.rotY(headYaw) * Quat.rotX(headPitch) * Quat.rotZ(roll))

        // brow: raise on demand and at random
        if (now >= nextBrow) {
            browStart = now
            nextBrow = now + 4.0 + random.nextDouble() * 4.0
        }
        val browK = now - browStart
        val browY = when {
            sulking -> -0.6
            browK in 0.0..BROW_SECONDS -> sin(browK / BROW_SECONDS * PI) * BROW_PX
            else -> 0.0
        }
        anims["brow"] = PartAnim(offset = Vec3(0.0, browY, 0.0))

        // lids: blink 3 ticks every 2.5-6 s
        if (now >= nextBlink + BLINK_SECONDS) {
            nextBlink = now + 2.5 + random.nextDouble() * 3.5
        }
        val closed = sleeping || now in nextBlink..(nextBlink + BLINK_SECONDS)
        val lid = PartAnim(scale = Vec3(1.0, if (closed) 1.0 else lidRest.toDouble(), 1.0))
        anims["lid_l"] = lid
        anims["lid_r"] = lid

        // mouth and nose: speaking
        val mouthY = when {
            speaking -> 0.6 + abs(sin(now * TALK_RATE)) * 1.4
            cheering -> 1.6
            sulking -> 0.6
            else -> 1.0
        }
        anims["mouth"] = PartAnim(scale = Vec3(1.0, mouthY, 1.0))
        val noseX = if (speaking) sin(now * TALK_RATE) * 0.12 else sin(now * 1.2) * 0.03
        anims["nose"] = PartAnim(rotation = Quat.rotX(noseX) * Quat.rotZ(sin(now * 2.1) * 0.05))
        return anims
    }

    private companion object {
        const val BOB_PX = 0.35
        const val BROW_PX = 0.9
        const val BROW_SECONDS = 0.9
        const val BLINK_SECONDS = 0.15
        const val TALK_RATE = 9.0
        const val HEAD_EASE = 0.35
        val MAX_YAW = Math.toRadians(35.0)
        val MAX_PITCH = Math.toRadians(25.0)
        val FOLLOW_MAX_TURN = Math.toRadians(100.0)
        const val LOOK_AROUND_YAW = 0.35
        const val CHEER_SECONDS = 1.0
        const val HOP_SECONDS = 0.5
        const val HOP_PX = 3.0
        const val SULK_SECONDS = 2.5
        val SULK_YAW = Math.toRadians(28.0)
        val SULK_PITCH = Math.toRadians(-12.0)
        val SLEEP_PITCH = Math.toRadians(-24.0)
    }
}
