package org.helix.cinematic

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the camera is during a shot.
 */
fun interface CameraPath {
    /**
     * @param t eased shot progress, 0..1.
     * @return the camera position, or null if a target it depends on is gone.
     */
    fun position(t: Double): Vec3?

    /** Built-in paths. */
    companion object {
        /**
         * @param point camera position.
         * @return a camera that does not move.
         */
        fun static(point: Vec3): CameraPath = CameraPath { point }

        /**
         * @param from start position.
         * @param to end position.
         * @return a straight flight.
         */
        fun linear(from: Vec3, to: Vec3): CameraPath = CameraPath { from.lerp(to, it) }

        /**
         * A Catmull-Rom spline through [points] (at least two) – smooth curves through hand-placed waypoints.
         *
         * @param points waypoints, flown through in order.
         * @return the spline path.
         */
        fun spline(vararg points: Vec3): CameraPath {
            require(points.size >= 2) { "a spline needs at least two points" }
            val p = points.toList()
            return CameraPath { t ->
                val segments = p.size - 1
                val scaled = (t.coerceIn(0.0, 1.0) * segments).coerceAtMost(segments - 1e-9)
                val i = scaled.toInt()
                val u = scaled - i
                val p0 = p[maxOf(i - 1, 0)]
                val p1 = p[i]
                val p2 = p[i + 1]
                val p3 = p[minOf(i + 2, p.size - 1)]
                catmullRom(p0, p1, p2, p3, u)
            }
        }

        /**
         * Circles around a (moving) [center] – a drone orbit that can fly in or out and climb or sink.
         *
         * @param center orbit centre.
         * @param startAngle start angle in degrees (0 = +Z of the centre, 90 = -X).
         * @param sweep degrees travelled during the shot (negative = the other way round).
         * @param radius horizontal distance at start and end.
         * @param height height above the centre at start and end.
         * @return the orbit path.
         */
        fun orbit(
            center: Target,
            startAngle: Double,
            sweep: Double,
            radius: ClosedFloatingPointRange<Double>,
            height: ClosedFloatingPointRange<Double>,
        ): CameraPath = CameraPath { t ->
            val c = center.position() ?: return@CameraPath null
            val angle = Math.toRadians(startAngle + sweep * t)
            val r = radius.start + (radius.endInclusive - radius.start) * t
            val h = height.start + (height.endInclusive - height.start) * t
            Vec3(c.x - sin(angle) * r, c.y + h, c.z + cos(angle) * r)
        }

        /**
         * Flies from a point straight towards (or away from) a (moving) target.
         *
         * @param from start position.
         * @param target the target.
         * @param stopDistance distance to the target at the end.
         * @return the dolly path.
         */
        fun dolly(from: Vec3, target: Target, stopDistance: Double): CameraPath = CameraPath { t ->
            val to = target.position() ?: return@CameraPath null
            val direction = to - from
            val length = direction.length()
            val end = if (length > 0) to - direction * (stopDistance / length) else to
            from.lerp(end, t)
        }

        private fun catmullRom(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
            val t2 = t * t
            val t3 = t2 * t
            /** One coordinate of the Catmull-Rom segment. */
            fun axis(a: Double, b: Double, c: Double, d: Double) =
                0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
            return Vec3(axis(p0.x, p1.x, p2.x, p3.x), axis(p0.y, p1.y, p2.y, p3.y), axis(p0.z, p1.z, p2.z, p3.z))
        }
    }
}
