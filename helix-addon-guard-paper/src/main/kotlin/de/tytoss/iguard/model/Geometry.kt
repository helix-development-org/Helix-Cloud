package de.tytoss.iguard.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 3D vector with the handful of operations the checks need. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)

    /** Squared length of the XZ projection. */
    fun horizontalLengthSquared() = x * x + z * z

    /** Squared 3D length. */
    fun lengthSquared() = horizontalLengthSquared() + y * y

    /** Unit-length copy (the zero vector stays zero). */
    fun normalized(): Vec3 {
        val length = sqrt(lengthSquared())
        return if (length < 1.0E-9) Vec3(0.0, 0.0, 0.0) else this * (1.0 / length)
    }

    companion object {
        /** Unit view direction for the given yaw/pitch (Minecraft convention). */
        fun direction(yaw: Float, pitch: Float): Vec3 {
            val yawRadians = Math.toRadians(-yaw - 90.0)
            val pitchRadians = Math.toRadians((-pitch).toDouble())
            val horizontal = cos(pitchRadians)
            return Vec3(cos(yawRadians) * horizontal, sin(pitchRadians), sin(yawRadians) * horizontal).normalized()
        }
    }
}

/** Axis-aligned bounding box. */
data class Box(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    /** A copy grown by [value] on every side. */
    fun expand(value: Double) = Box(minX - value, minY - value, minZ - value, maxX + value, maxY + value, maxZ + value)

    /** True when the two boxes overlap (exclusive bounds). */
    fun intersects(other: Box) = maxX > other.minX && minX < other.maxX &&
        maxY > other.minY && minY < other.maxY && maxZ > other.minZ && minZ < other.maxZ

    /** Slab-test ray/box intersection distance from [origin] along [direction], or null past [maximum]. */
    fun rayDistance(origin: Vec3, direction: Vec3, maximum: Double): Double? {
        var near = 0.0
        var far = maximum
        val origins = doubleArrayOf(origin.x, origin.y, origin.z)
        val directions = doubleArrayOf(direction.x, direction.y, direction.z)
        val minimums = doubleArrayOf(minX, minY, minZ)
        val maximums = doubleArrayOf(maxX, maxY, maxZ)
        for (axis in 0..2) {
            if (abs(directions[axis]) < 1.0E-9) {
                if (origins[axis] < minimums[axis] || origins[axis] > maximums[axis]) return null
            } else {
                val first = (minimums[axis] - origins[axis]) / directions[axis]
                val second = (maximums[axis] - origins[axis]) / directions[axis]
                near = max(near, min(first, second))
                far = min(far, max(first, second))
                if (near > far) return null
            }
        }
        return near.takeIf { it in 0.0..maximum }
    }
}
