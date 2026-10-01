package org.helix.cinematic

import org.bukkit.Location
import org.bukkit.World
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A point or direction in world space (pure, so paths stay testable without a server).
 *
 * @property x x coordinate.
 * @property y y coordinate.
 * @property z z coordinate.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    /** @return this + [o]. */
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    /** @return this - [o]. */
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    /** @return this scaled by [k]. */
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)

    /** @return the length. */
    fun length(): Double = sqrt(x * x + y * y + z * z)

    /** @return the point between this (t = 0) and [to] (t = 1). */
    fun lerp(to: Vec3, t: Double) = Vec3(x + (to.x - x) * t, y + (to.y - y) * t, z + (to.z - z) * t)

    /**
     * @param world world of the location.
     * @param rotation view direction.
     * @return a Bukkit location at this point.
     */
    fun toLocation(world: World, rotation: Rotation = Rotation.NONE) = Location(world, x, y, z, rotation.yaw, rotation.pitch)

    /** Constructors. */
    companion object {
        /** The origin. */
        val ZERO = Vec3(0.0, 0.0, 0.0)

        /** @return the position of [location]. */
        fun of(location: Location) = Vec3(location.x, location.y, location.z)
    }
}

/**
 * A view direction in Minecraft degrees (yaw 0 = +Z / south, 90 = -X / west; pitch positive = down).
 *
 * @property yaw horizontal angle.
 * @property pitch vertical angle.
 */
data class Rotation(val yaw: Float, val pitch: Float) {
    /** Look-at helpers. */
    companion object {
        /** Facing south, level. */
        val NONE = Rotation(0f, 0f)

        /**
         * @param from viewer position.
         * @param to looked-at position.
         * @return the rotation looking from [from] at [to].
         */
        fun lookingAt(from: Vec3, to: Vec3): Rotation {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val pitch = Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
            return Rotation(yaw, pitch)
        }

        /**
         * Interpolates along the shorter way round.
         *
         * @param a rotation at t = 0.
         * @param b rotation at t = 1.
         * @param t progress.
         * @return the rotation in between.
         */
        fun lerp(a: Rotation, b: Rotation, t: Double): Rotation {
            var dyaw = (b.yaw - a.yaw) % 360f
            if (dyaw > 180f) dyaw -= 360f
            if (dyaw < -180f) dyaw += 360f
            return Rotation((a.yaw + dyaw * t).toFloat(), (a.pitch + (b.pitch - a.pitch) * t).toFloat())
        }
    }
}
