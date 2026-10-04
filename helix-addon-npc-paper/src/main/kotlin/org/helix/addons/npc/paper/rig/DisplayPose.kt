package org.helix.addons.npc.paper.rig

import org.bukkit.entity.ItemDisplay
import org.bukkit.util.Transformation
import org.helix.addons.npc.paper.villager.PartTransform
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/**
 * Sending solved part transformations to item displays (shared by villager and generic rigs).
 */
object DisplayPose {
    /** Changes below this are not re-sent. */
    const val EPSILON = 1e-4

    /**
     * @param t a solved transformation.
     * @return the Bukkit transformation.
     */
    fun toBukkit(t: PartTransform): Transformation = Transformation(
        Vector3f(t.translation.x.toFloat(), t.translation.y.toFloat(), t.translation.z.toFloat()),
        Quaternionf(t.rotation.x.toFloat(), t.rotation.y.toFloat(), t.rotation.z.toFloat(), t.rotation.w.toFloat()),
        Vector3f(t.scale.x.toFloat(), t.scale.y.toFloat(), t.scale.z.toFloat()),
        Quaternionf(),
    )

    /**
     * @param a a transformation.
     * @param b another.
     * @return whether they differ by less than [EPSILON] in every component.
     */
    fun close(a: PartTransform, b: PartTransform): Boolean =
        near(a.translation.x, b.translation.x) && near(a.translation.y, b.translation.y) &&
            near(a.translation.z, b.translation.z) && near(a.rotation.x, b.rotation.x) &&
            near(a.rotation.y, b.rotation.y) && near(a.rotation.z, b.rotation.z) &&
            near(a.rotation.w, b.rotation.w) && near(a.scale.x, b.scale.x) && near(a.scale.y, b.scale.y) &&
            near(a.scale.z, b.scale.z)

    /**
     * Sends a pose: every part whose transformation changed since the last send gets it with a fresh client
     * interpolation of [duration] ticks.
     *
     * @param pose solved transformation per part.
     * @param displays display per part.
     * @param sent last sent transformation per part (updated).
     * @param restart alternating flag: the client only restarts interpolation when the delay value is re-sent, and
     *   unchanged values are not re-sent, so callers alternate it per update.
     * @param duration interpolation ticks.
     */
    fun send(
        pose: Map<String, PartTransform>,
        displays: Map<String, ItemDisplay>,
        sent: MutableMap<String, PartTransform>,
        restart: Boolean,
        duration: Int,
    ) {
        pose.forEach { (name, transform) ->
            val display = displays[name] ?: return@forEach
            val last = sent[name]
            if (last != null && close(last, transform)) return@forEach
            display.transformation = toBukkit(transform)
            display.interpolationDelay = if (restart) 0 else -1
            display.interpolationDuration = duration
            sent[name] = transform
        }
    }

    private fun near(a: Double, b: Double): Boolean = abs(a - b) < EPSILON
}
