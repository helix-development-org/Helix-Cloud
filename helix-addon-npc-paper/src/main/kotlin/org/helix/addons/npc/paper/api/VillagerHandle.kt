package org.helix.addons.npc.paper.api

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.function.Consumer

/**
 * An animated villager spawned through [HelixNpcApi.spawnVillager]. Main thread only.
 */
interface VillagerHandle {
    /**
     * @return the villager id (e.g. `mira`).
     */
    fun villagerId(): String

    /**
     * Speaks a line: chat line to players within 16 blocks, "Hmm" plus babble, mouth animation. Lines queue up.
     *
     * @param text plain text.
     * @return `false` when the speech queue is full.
     */
    fun say(text: String): Boolean

    /** Raises the brow (address, offer, surprise). */
    fun raiseBrow()

    /** Plays the cheer (victory, purchase, upgrade). */
    fun cheer()

    /** Plays the sulk (defeat, error). */
    fun sulk()

    /**
     * Puts the villager to sleep or wakes it.
     *
     * @param asleep `true` to sleep.
     */
    fun sleep(asleep: Boolean)

    /**
     * Moves the villager (respawns its entities at the new place).
     *
     * @param location new feet position; the yaw is the facing.
     */
    fun teleport(location: Location)

    /**
     * @return a copy of the current feet position.
     */
    fun location(): Location

    /**
     * Registers a right-click callback (debounced; the click also raises the brow). Several callbacks may be added.
     *
     * @param callback receives the clicking player.
     */
    fun onClick(callback: Consumer<Player>)

    /**
     * @param entity an entity.
     * @return whether [entity] is this villager's click hitbox.
     */
    fun owns(entity: Entity): Boolean

    /**
     * @return `false` once [remove] was called (or the owner plugin disabled).
     */
    fun isValid(): Boolean

    /** Removes the villager for good. */
    fun remove()
}
