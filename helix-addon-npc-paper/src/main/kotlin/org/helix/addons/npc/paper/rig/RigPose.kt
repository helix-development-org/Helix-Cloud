package org.helix.addons.npc.paper.rig

import kotlinx.serialization.json.Json
import org.helix.addons.npc.paper.villager.Kinematics
import org.helix.addons.npc.paper.villager.PartAnim
import org.helix.addons.npc.paper.villager.PartTransform
import org.helix.addons.npc.paper.villager.Quat
import org.helix.addons.npc.paper.villager.RigSpec
import org.helix.addons.npc.paper.villager.Vec3

/**
 * Pose state of a generic rig (pure): per-part animation relative to the rest pose plus a uniform rig scale, solved
 * with [Kinematics.solve].
 *
 * @property spec the rig.
 */
class RigPose(val spec: RigSpec) {
    private val anims = HashMap<String, PartAnim>()
    private val names = spec.parts.map { it.name }.toSet()

    /** Uniform scale of the whole rig about the feet (1 = model size). */
    var scale: Double = 1.0

    /**
     * @param part a part name.
     * @return whether the rig has that part.
     */
    fun has(part: String): Boolean = part in names

    /**
     * Sets a part's rotation (Euler degrees, Blockbench order ZYX) relative to its rest pose.
     *
     * @param part part name; unknown parts are ignored.
     * @param x degrees about X.
     * @param y degrees about Y.
     * @param z degrees about Z.
     */
    fun rotate(part: String, x: Double, y: Double, z: Double) =
        update(part) { it.copy(rotation = if (x == 0.0 && y == 0.0 && z == 0.0) Quat.IDENTITY else Quat.eulerZYX(listOf(x, y, z))) }

    /**
     * Sets a part's offset in model pixels (parent frame).
     *
     * @param part part name; unknown parts are ignored.
     * @param x pixels.
     * @param y pixels.
     * @param z pixels.
     */
    fun offset(part: String, x: Double, y: Double, z: Double) = update(part) { it.copy(offset = Vec3(x, y, z)) }

    /**
     * Sets a part's own scale.
     *
     * @param part part name; unknown parts are ignored.
     * @param x factor.
     * @param y factor.
     * @param z factor.
     */
    fun scale(part: String, x: Double, y: Double, z: Double) = update(part) { it.copy(scale = Vec3(x, y, z)) }

    /**
     * Resets a part to its rest pose.
     *
     * @param part part name.
     */
    fun reset(part: String) {
        anims.remove(part)
    }

    /** Resets every part (the rig scale stays). */
    fun resetAll() = anims.clear()

    /**
     * @return display transformation per part with a model, rig scale applied about the feet.
     */
    fun solve(): Map<String, PartTransform> {
        val pose = Kinematics.solve(spec, anims)
        if (scale == 1.0) return pose
        return pose.mapValues { (_, t) -> PartTransform(t.translation * scale, t.rotation, t.scale * scale) }
    }

    private fun update(part: String, change: (PartAnim) -> PartAnim) {
        if (part !in names) return
        anims[part] = change(anims[part] ?: PartAnim.NONE)
    }
}

/**
 * Parsing and validation of rig descriptions passed through the public API.
 */
object RigSpecs {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param raw rig description JSON ([RigSpec] shape).
     * @return the rig.
     * @throws IllegalArgumentException when the JSON is malformed or the part tree is inconsistent.
     */
    fun parse(raw: String): RigSpec {
        val spec = runCatching { json.decodeFromString<RigSpec>(raw) }
            .getOrElse { throw IllegalArgumentException("rig description unreadable: ${it.message}", it) }
        validate(spec)
        return spec
    }

    /**
     * Checks names, parents (must precede their children) and vector sizes.
     *
     * @param spec the rig.
     * @throws IllegalArgumentException on the first problem.
     */
    fun validate(spec: RigSpec) {
        require(spec.parts.isNotEmpty()) { "rig has no parts" }
        val seen = HashSet<String>()
        spec.parts.forEach { part ->
            require(part.name.isNotBlank()) { "part without name" }
            require(seen.add(part.name)) { "duplicate part '${part.name}'" }
            require(part.parent == null || part.parent in seen) { "part '${part.name}': parent '${part.parent}' missing or after it" }
            require(part.pivot.size == 3) { "part '${part.name}': pivot needs 3 numbers" }
            require(part.rest.size == 3) { "part '${part.name}': rest needs 3 numbers" }
            require(part.scale > 0.0) { "part '${part.name}': scale must be positive" }
        }
    }
}
