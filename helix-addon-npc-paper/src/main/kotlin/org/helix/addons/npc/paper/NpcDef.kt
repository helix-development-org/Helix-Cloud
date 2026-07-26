package org.helix.addons.npc.paper

import kotlinx.serialization.Serializable

/**
 * A network-wide NPC definition, as served by and sent to the `helix.npc`
 * node addon.
 *
 * Mirrors the node-side document shape verbatim, so the serialized field
 * names are part of the control-API contract.
 *
 * @property id unique NPC id (lowercase).
 * @property task task this NPC lives on, or `*` for every task.
 * @property world Bukkit world name.
 * @property x world x coordinate.
 * @property y world y coordinate.
 * @property z world z coordinate.
 * @property yaw body/head yaw in degrees.
 * @property pitch head pitch in degrees.
 * @property skin skin source: a Minecraft account name, or `self` to mirror
 *   the viewing player's own skin.
 * @property hologramLines floating text lines, top first; MiniMessage markup.
 * @property lookMode head behaviour: `none`, `nearest` or `player`.
 * @property interactAction command the clicking player runs on right-click,
 *   or `null` for a non-interactive NPC.
 */
@Serializable
data class NpcDef(
    val id: String,
    val task: String = "*",
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val skin: String = "self",
    val hologramLines: List<String> = emptyList(),
    val lookMode: String = "none",
    val interactAction: String? = null,
)
