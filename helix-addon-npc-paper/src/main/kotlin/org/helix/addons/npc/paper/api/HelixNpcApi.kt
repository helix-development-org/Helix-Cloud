package org.helix.addons.npc.paper.api

import org.bukkit.Location
import org.bukkit.plugin.Plugin

/**
 * Public entry point of Helix-NPC for other plugins: animated villagers and generic display-entity rigs.
 *
 * Obtain it from the Bukkit services manager (`Bukkit.getServicesManager().load(HelixNpcApi::class.java)`) or by
 * casting the `Helix-NPC` plugin; declare `softdepend: [Helix-NPC]` (or `depend`) so its classes are visible.
 *
 * Every signature uses Java/Bukkit types only: the Helix-NPC jar bundles its own Kotlin runtime, so Kotlin function
 * types or collections of another runtime must not cross the plugin boundary. Callbacks are
 * [java.util.function.Consumer]s. All methods must be called on the server main thread.
 *
 * Everything spawned through this API is non-persistent, ticked by Helix-NPC (pose updates every 2 ticks with client
 * interpolation, only while a player is near) and removed automatically when the owning plugin disables.
 */
interface HelixNpcApi {
    /**
     * @return ids of the villager rigs this build ships (e.g. `bertold`, `mira`), sorted.
     */
    fun villagerIds(): List<String>

    /**
     * Spawns an animated villager (design concept §12): idle bob, blinking, speech, reactions and a click hitbox.
     *
     * @param owner plugin that owns the villager; it is removed when that plugin disables.
     * @param villagerId villager id, see [villagerIds].
     * @param location feet position; the yaw is the facing.
     * @param followPlayers whether the head follows the nearest player (radius 6).
     * @return the handle, or `null` when the villager id is unknown.
     */
    fun spawnVillager(owner: Plugin, villagerId: String, location: Location, followPlayers: Boolean): VillagerHandle?

    /**
     * Spawns a generic rig: one item display per model part, item model `<namespace>:<modelId>/<part>`, posed by
     * forward kinematics from the rig description.
     *
     * The rig description is the JSON shape Helix-NPC's pack generator writes per model:
     * `{"parts":[{"name","parent","pivot":[x,y,z],"rest":[x,y,z],"model":true,"scale":1.0}…],"height":px}` with
     * parts in parent-before-child order, coordinates in Blockbench pixels, feet centre at (8, 0, 8), face to -Z.
     * Parsed descriptions are cached by their text, so passing the same string for every spawn is cheap. The display
     * entities appear on the next server tick, so a pose, scale or tint set right after this call applies from the
     * first frame (e.g. scale 0 for a pop-in).
     *
     * @param owner plugin that owns the rig; it is removed when that plugin disables.
     * @param namespace resource-pack namespace of the part item models.
     * @param modelId model path below the namespace, e.g. `mob/zombie`.
     * @param rigJson the rig description.
     * @param location feet position; the yaw is the facing.
     * @return the handle, or `null` when the description is invalid.
     */
    fun spawnRig(owner: Plugin, namespace: String, modelId: String, rigJson: String, location: Location): RigHandle?
}
