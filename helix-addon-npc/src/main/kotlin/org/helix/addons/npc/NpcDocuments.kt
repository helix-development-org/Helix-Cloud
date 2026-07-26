package org.helix.addons.npc

import kotlinx.serialization.Serializable

/**
 * A single network-wide NPC definition.
 *
 * This type is the persistence document, the `npc.get`/`npc.list` response
 * element and the `npc.save` request payload, so its serialized field names
 * are part of the control-API contract shared with the Paper component.
 *
 * @property id unique, command-safe NPC id (`[A-Za-z0-9_.-]`, lowercased).
 * @property task name of the task (server type) the NPC lives on, or `*`
 *   for every task.
 * @property world name of the Bukkit world the NPC spawns in.
 * @property x world x coordinate of the NPC.
 * @property y world y coordinate of the NPC.
 * @property z world z coordinate of the NPC.
 * @property yaw horizontal body/head rotation in degrees.
 * @property pitch vertical head rotation in degrees.
 * @property skin skin source: a Minecraft account name, or `self` to mirror
 *   the viewing player's own skin.
 * @property hologramLines floating text lines rendered above the NPC, top
 *   line first; MiniMessage markup is allowed.
 * @property lookMode head behaviour: `none`, `nearest` or `player` (the
 *   latter two both turn toward the closest player).
 * @property interactAction optional command the clicking player runs on
 *   right-click (for example `server Lobby`); `null` disables interaction.
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

/**
 * Generic acknowledgement of a mutating NPC action.
 *
 * @property ok whether the mutation succeeded.
 * @property removed whether a delete actually removed an existing NPC.
 */
@Serializable
data class NpcAck(
    val ok: Boolean,
    val removed: Boolean = false,
)
