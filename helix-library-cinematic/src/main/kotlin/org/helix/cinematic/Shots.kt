package org.helix.cinematic

import org.bukkit.FluidCollisionMode
import org.bukkit.World
import org.bukkit.util.Vector
import kotlin.random.Random

/**
 * Ready-made shots.
 */
object Shots {
    /**
     * A random drone shot around [target] (like GTA's idle cinematic): starts 9–14 blocks away and 1–5 blocks up,
     * flies in to 4–6 blocks while orbiting 15–30°, always looking at the target. Candidates whose camera would sit
     * inside blocks or lose sight of the target are discarded ([clear]); after [attempts] tries a close orbit is
     * used.
     *
     * @param world world of the target (for the sight checks).
     * @param target filmed target.
     * @param ticks shot duration.
     * @param random randomness source.
     * @param attempts tries before the close fallback.
     * @return the shot.
     */
    fun drone(world: World, target: Target, ticks: Int, random: Random = Random.Default, attempts: Int = 16): Shot {
        repeat(attempts) {
            val path = CameraPath.orbit(
                center = target,
                startAngle = random.nextDouble(0.0, 360.0),
                sweep = random.nextDouble(15.0, 30.0) * (if (random.nextBoolean()) 1 else -1),
                radius = random.nextDouble(9.0, 14.0)..random.nextDouble(4.0, 6.0),
                height = random.nextDouble(1.0, 5.0)..random.nextDouble(0.3, 2.0),
            )
            if (clear(world, path, target)) return Shot(ticks, path, Look.at(target))
        }
        val close = CameraPath.orbit(target, random.nextDouble(0.0, 360.0), 20.0, 4.0..3.0, 1.2..0.6)
        return Shot(ticks, close, Look.at(target))
    }

    /**
     * A random fly-in straight at [target] – the image turns slowly while the camera rushes or glides in (like a
     * phone/TikTok cinematic): starts 9–18 blocks away at a random height (low over the ground up to a high crane),
     * ends 2.5–4 blocks away, turning 8–25° around the target on the way. Speed varies per shot through the
     * duration ([minTicks]..[maxTicks]) and the easing (rush in and brake, glide, accelerate). Checked like [drone].
     *
     * @param world world of the target.
     * @param target filmed target.
     * @param minTicks shortest shot.
     * @param maxTicks longest shot.
     * @param random randomness source.
     * @param attempts tries before a close fallback.
     * @return the shot.
     */
    fun flyIn(world: World, target: Target, minTicks: Int = 60, maxTicks: Int = 160, random: Random = Random.Default, attempts: Int = 16): Shot {
        val easings = listOf(Easing.OUT, Easing.SMOOTH, Easing.SINE, Easing.IN_OUT, Easing.OUT)
        repeat(attempts) {
            val high = random.nextInt(4) == 0
            val path = CameraPath.orbit(
                center = target,
                startAngle = random.nextDouble(0.0, 360.0),
                sweep = random.nextDouble(8.0, 25.0) * (if (random.nextBoolean()) 1 else -1),
                radius = random.nextDouble(9.0, 18.0)..random.nextDouble(2.5, 4.0),
                height = (if (high) random.nextDouble(6.0, 10.0) else random.nextDouble(-0.6, 3.0))..random.nextDouble(-0.2, 0.8),
            )
            if (clear(world, path, target)) {
                return Shot(random.nextInt(minTicks, maxTicks + 1), path, Look.at(target), easings[random.nextInt(easings.size)])
            }
        }
        val close = CameraPath.orbit(target, random.nextDouble(0.0, 360.0), 15.0, 5.0..3.0, 1.0..0.3)
        return Shot(random.nextInt(minTicks, maxTicks + 1), close, Look.at(target), Easing.SINE)
    }

    /**
     * Whether the camera stays in free space and keeps the target in sight along [path] (sampled).
     *
     * @param world world of the path.
     * @param path camera path.
     * @param target filmed target.
     * @param samples progress values checked.
     * @return true if every sample is clear.
     */
    fun clear(world: World, path: CameraPath, target: Target, samples: List<Double> = listOf(0.0, 0.35, 0.7, 1.0)): Boolean =
        samples.all { t ->
            val camera = path.position(t) ?: return@all false
            val to = target.position() ?: return@all false
            val at = camera.toLocation(world)
            if (!at.block.isPassable) return@all false
            val ray = Vector(to.x - camera.x, to.y - camera.y, to.z - camera.z)
            val distance = ray.length()
            distance < 0.1 || world.rayTraceBlocks(at, ray.normalize(), distance, FluidCollisionMode.NEVER, true) == null
        }
}
