package org.helix.cinematic

/**
 * Where the camera looks during a shot.
 */
fun interface Look {
    /**
     * @param camera current camera position.
     * @param t eased shot progress, 0..1.
     * @param path the shot's path (for looking along it).
     * @return the view direction, or null if a target it depends on is gone.
     */
    fun rotation(camera: Vec3, t: Double, path: CameraPath): Rotation?

    /** Built-in looks. */
    companion object {
        /**
         * @param target looked-at target (followed if it moves).
         * @return a camera always looking at the target.
         */
        fun at(target: Target): Look = Look { camera, _, _ -> target.position()?.let { Rotation.lookingAt(camera, it) } }

        /**
         * @param rotation fixed view direction.
         * @return a camera that never turns.
         */
        fun fixed(rotation: Rotation): Look = Look { _, _, _ -> rotation }

        /**
         * @param from view direction at the start.
         * @param to view direction at the end.
         * @return a pan between two directions.
         */
        fun pan(from: Rotation, to: Rotation): Look = Look { _, t, _ -> Rotation.lerp(from, to, t) }

        /**
         * @return a camera looking in its flight direction (like a fly-through).
         */
        fun along(): Look = Look { camera, t, path ->
            val ahead = path.position((t + 0.02).coerceAtMost(1.0)) ?: return@Look null
            if ((ahead - camera).length() > 1e-6) return@Look Rotation.lookingAt(camera, ahead)
            // am Ende des Pfads: Richtung aus dem letzten Stück
            val behind = path.position((t - 0.02).coerceAtLeast(0.0)) ?: return@Look null
            if ((camera - behind).length() > 1e-6) Rotation.lookingAt(behind, camera) else Rotation.NONE
        }
    }
}
