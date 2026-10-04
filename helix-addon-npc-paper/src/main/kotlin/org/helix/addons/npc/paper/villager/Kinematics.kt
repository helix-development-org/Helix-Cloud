package org.helix.addons.npc.paper.villager

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable 3D vector.
 *
 * @property x x component.
 * @property y y component.
 * @property z z component.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    /** Component-wise sum. */
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)

    /** Component-wise difference. */
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)

    /** Uniform scaling. */
    operator fun times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)

    /** Component-wise product. */
    fun mul(o: Vec3): Vec3 = Vec3(x * o.x, y * o.y, z * o.z)

    /** Constants. */
    companion object {
        /** The zero vector. */
        val ZERO = Vec3(0.0, 0.0, 0.0)

        /** The one vector (identity scale). */
        val ONE = Vec3(1.0, 1.0, 1.0)

        /**
         * @param list three numbers.
         * @return the vector.
         */
        fun of(list: List<Double>): Vec3 = Vec3(list[0], list[1], list[2])
    }
}

/**
 * Immutable unit quaternion (rotation).
 *
 * @property x x component.
 * @property y y component.
 * @property z z component.
 * @property w scalar component.
 */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    /** Hamilton product: applies [o] first, then this. */
    operator fun times(o: Quat): Quat = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /**
     * @param v a vector.
     * @return [v] rotated by this quaternion.
     */
    fun rotate(v: Vec3): Vec3 {
        // t = 2 * cross(q.xyz, v); v' = v + w * t + cross(q.xyz, t)
        val tx = 2 * (y * v.z - z * v.y)
        val ty = 2 * (z * v.x - x * v.z)
        val tz = 2 * (x * v.y - y * v.x)
        return Vec3(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    /** Factories. */
    companion object {
        /** No rotation. */
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        /** @return rotation by [rad] radians about +X. */
        fun rotX(rad: Double): Quat = Quat(sin(rad / 2), 0.0, 0.0, cos(rad / 2))

        /** @return rotation by [rad] radians about +Y. */
        fun rotY(rad: Double): Quat = Quat(0.0, sin(rad / 2), 0.0, cos(rad / 2))

        /** @return rotation by [rad] radians about +Z. */
        fun rotZ(rad: Double): Quat = Quat(0.0, 0.0, sin(rad / 2), cos(rad / 2))

        /**
         * Blockbench group rotation (Euler degrees, three.js order `ZYX`: X applied first, then Y, then Z).
         *
         * @param deg x, y, z in degrees.
         * @return the rotation.
         */
        fun eulerZYX(deg: List<Double>): Quat =
            rotZ(Math.toRadians(deg[2])) * rotY(Math.toRadians(deg[1])) * rotX(Math.toRadians(deg[0]))
    }
}

/**
 * Animation state of one part, relative to its rest pose and applied about its pivot.
 *
 * @property offset translation in model pixels (in the parent's frame).
 * @property rotation rotation about the pivot.
 * @property scale scale of the part's own geometry (not inherited by children).
 */
data class PartAnim(
    val offset: Vec3 = Vec3.ZERO,
    val rotation: Quat = Quat.IDENTITY,
    val scale: Vec3 = Vec3.ONE,
) {
    /** Constants. */
    companion object {
        /** Rest pose. */
        val NONE = PartAnim()
    }
}

/**
 * Final display transformation of one part, in the item display's own frame (blocks).
 *
 * @property translation display translation in blocks.
 * @property rotation left rotation.
 * @property scale scale.
 */
data class PartTransform(val translation: Vec3, val rotation: Quat, val scale: Vec3)

/**
 * Forward kinematics of a villager rig.
 *
 * Each part's item model is shifted so its pivot sits at the model centre (8, 8, 8), which the item display renders
 * at the entity origin. The renderer applies, in this order: entity yaw, our transformation, a 180° turn about Y
 * (`ItemDisplayRenderer`), the item model. Blockbench models face -Z; the 180° turn makes them face the entity yaw,
 * so the rig is solved in model space and the result conjugated by that turn ([toDisplay]).
 */
object Kinematics {
    /** Feet centre of the models in pixels: the entity origin. */
    val FEET = Vec3(8.0, 0.0, 8.0)

    /** Pixels per block. */
    const val PIXELS = 16.0

    /**
     * Solves the rig for one frame.
     *
     * @param spec the rig.
     * @param anims animation state per part name; missing parts stay at rest.
     * @return display transformation per part that has a model.
     */
    fun solve(spec: RigSpec, anims: Map<String, PartAnim>): Map<String, PartTransform> {
        val world = HashMap<String, Pair<Quat, Vec3>>()
        val out = LinkedHashMap<String, PartTransform>()
        for (part in spec.parts) {
            val (parentRot, parentPos) = part.parent?.let { world[it] } ?: (Quat.IDENTITY to Vec3.ZERO)
            val anim = anims[part.name] ?: PartAnim.NONE
            val pivot = Vec3.of(part.pivot)
            val localRot = Quat.eulerZYX(part.rest) * anim.rotation
            val pivotWorld = parentRot.rotate(pivot + anim.offset) + parentPos
            val rot = parentRot * localRot
            // x -> rot * (x - pivot) + pivotWorld
            world[part.name] = rot to (pivotWorld - rot.rotate(pivot))
            if (part.model) {
                out[part.name] = toDisplay(
                    PartTransform((pivotWorld - FEET) * (1.0 / PIXELS), rot, anim.scale * part.scale),
                )
            }
        }
        return out
    }

    /**
     * Conjugates a model-space transformation by the item display's built-in 180° turn about Y.
     *
     * @param t transformation in model space (blocks).
     * @return the same transformation in the display frame.
     */
    fun toDisplay(t: PartTransform): PartTransform = PartTransform(
        Vec3(-t.translation.x, t.translation.y, -t.translation.z),
        Quat(-t.rotation.x, t.rotation.y, -t.rotation.z, t.rotation.w),
        t.scale,
    )

    /**
     * Head angles (model space) that make a model facing -Z look at a target.
     *
     * @param npcYaw entity yaw in Minecraft degrees (0 = south/+Z, 90 = west).
     * @param dx target x minus head x (world, blocks).
     * @param dy target y minus head y.
     * @param dz target z minus head z.
     * @return yaw (rotation about +Y; positive turns the face from -Z towards -X) and pitch (rotation about +X;
     *   positive looks up), both in radians.
     */
    fun lookAngles(npcYaw: Double, dx: Double, dy: Double, dz: Double): Pair<Double, Double> {
        // world -> model: inverse of Ry(-yaw) * Ry(pi) = Ry(pi) * Ry(yaw)
        val m = (Quat.rotY(Math.PI) * Quat.rotY(Math.toRadians(npcYaw))).rotate(Vec3(dx, dy, dz))
        val yaw = atan2(-m.x, -m.z)
        val pitch = atan2(m.y, sqrt(m.x * m.x + m.z * m.z))
        return yaw to pitch
    }
}
