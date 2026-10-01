package org.helix.cinematic

import org.bukkit.Location
import org.bukkit.entity.Entity

/**
 * Something a camera orbits around or looks at; evaluated every update, so moving entities are followed.
 */
fun interface Target {
    /** @return the current position, or null if the target is gone (the playback then stops). */
    fun position(): Vec3?

    /** Constructors. */
    companion object {
        /**
         * @param location a fixed point.
         * @return a target at that point.
         */
        fun at(location: Location): Target = Vec3.of(location).let { p -> Target { p } }

        /**
         * @param point a fixed point.
         * @return a target at that point.
         */
        fun at(point: Vec3): Target = Target { point }

        /**
         * @param entity followed entity.
         * @param height offset above its feet (e.g. 1.3 for a player's chest, 1.62 for the eyes).
         * @return a target following the entity.
         */
        fun of(entity: Entity, height: Double = 1.3): Target =
            Target { if (entity.isValid) Vec3.of(entity.location) + Vec3(0.0, height, 0.0) else null }
    }
}
