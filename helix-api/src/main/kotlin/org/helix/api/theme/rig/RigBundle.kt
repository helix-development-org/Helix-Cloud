package org.helix.api.theme.rig

import kotlinx.serialization.Serializable

/**
 * Rig description of a theme's villager models (`Helix/themes/<id>/rigs.json`), written by the theme kit from the
 * Blockbench files and served to the NPC component by the node. Same JSON shape as the former bundled
 * `helix_villager/rigs.json`.
 *
 * @property villagers rig per villager id.
 */
@Serializable
data class RigBundle(val villagers: Map<String, RigSpec> = emptyMap())

/**
 * Rig of one villager: its parts in parent-before-child order. Coordinates are Blockbench model pixels; the feet
 * centre is at (8, 0, 8) and the face points to -Z.
 *
 * @property parts the parts, parents first.
 * @property height top of the highest element above the feet, in pixels.
 */
@Serializable
data class RigSpec(val parts: List<RigPart>, val height: Double = 32.0)

/**
 * One rig part (a Blockbench group).
 *
 * @property name group name, e.g. `head`.
 * @property parent parent part name, or `null` for the top part.
 * @property pivot rotation pivot in model pixels.
 * @property rest rest rotation as Euler degrees (Blockbench group rotation, ZYX order).
 * @property model whether the part has elements of its own (and thus an item model `<id>/<name>`).
 * @property scale factor the item model was shrunk by to fit the Java element bounds; the display scales it back.
 */
@Serializable
data class RigPart(
    val name: String,
    val parent: String? = null,
    val pivot: List<Double>,
    val rest: List<Double> = listOf(0.0, 0.0, 0.0),
    val model: Boolean = true,
    val scale: Double = 1.0,
)
