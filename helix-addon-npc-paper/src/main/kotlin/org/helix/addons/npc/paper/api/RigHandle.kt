package org.helix.addons.npc.paper.api

import org.bukkit.Location
import org.bukkit.entity.Entity

/**
 * A generic display-entity rig spawned through [HelixNpcApi.spawnRig]. Main thread only.
 *
 * Pose setters store the target pose; Helix-NPC solves the forward kinematics every 2 ticks and sends the changed part
 * transformations with client interpolation (only while a player is within 48 blocks). Part poses are relative to the
 * rest pose and applied about the part's pivot; rotations and offsets are inherited by child parts, part scales are not
 * (use [setScale] to scale the whole rig).
 */
interface RigHandle {
    /**
     * @return all part names in parent-before-child order (parts without own model included).
     */
    fun parts(): List<String>

    /**
     * @return height of the model in blocks (top of the highest element above the feet, unscaled).
     */
    fun height(): Double

    /**
     * Sets a part's rotation relative to its rest pose.
     *
     * @param part part name.
     * @param x rotation about X in degrees (Blockbench convention, Euler order ZYX).
     * @param y rotation about Y in degrees.
     * @param z rotation about Z in degrees.
     */
    fun setPartRotation(part: String, x: Double, y: Double, z: Double)

    /**
     * Sets a part's translation relative to its rest position.
     *
     * @param part part name.
     * @param x offset in model pixels (1/16 block), in the parent's frame.
     * @param y offset in pixels.
     * @param z offset in pixels.
     */
    fun setPartOffset(part: String, x: Double, y: Double, z: Double)

    /**
     * Scales a part's own geometry about its pivot (children keep their size).
     *
     * @param part part name.
     * @param x scale factor.
     * @param y scale factor.
     * @param z scale factor.
     */
    fun setPartScale(part: String, x: Double, y: Double, z: Double)

    /**
     * Resets one part to its rest pose.
     *
     * @param part part name.
     */
    fun resetPart(part: String)

    /** Resets every part to its rest pose (the rig scale stays). */
    fun resetPose()

    /**
     * Scales the whole rig about its feet.
     *
     * @param scale factor (1 = model size; 0 hides it).
     */
    fun setScale(scale: Double)

    /**
     * Tints a part through its item's `DYED_COLOR` component (only faces the item model tints, e.g. with a
     * `minecraft:dye` tint source, change).
     *
     * @param part part name.
     * @param rgb colour as 0xRRGGBB, or `-1` to remove the tint.
     */
    fun setTint(part: String, rgb: Int)

    /**
     * Swaps a part's item model, e.g. to a white flash variant.
     *
     * @param part part name.
     * @param itemModel namespaced item model key (`ns:path`), or `null` to restore `<namespace>:<modelId>/<part>`.
     */
    fun setPartModel(part: String, itemModel: String?)

    /**
     * Outlines the whole rig.
     *
     * @param rgb glow colour as 0xRRGGBB, or `-1` to switch the glow off.
     */
    fun setGlow(rgb: Int)

    /**
     * Overrides the light level the parts render with.
     *
     * @param level block and sky light 0..15, or `-1` for the light at the rig's position.
     */
    fun setBrightness(level: Int)

    /**
     * Moves the rig (interpolated over 2 ticks). Detaches it from a followed entity.
     *
     * @param location new feet position; the yaw is the facing.
     */
    fun teleport(location: Location)

    /**
     * Makes the rig follow an entity every tick: its position and, for living entities, the body yaw (else the
     * entity yaw). When the entity becomes invalid (dead, removed, unloaded) the rig stays at the last position and
     * detaches.
     *
     * @param entity entity to follow, or `null` to detach.
     */
    fun attachTo(entity: Entity?)

    /**
     * @return the followed entity, or `null`.
     */
    fun attachedEntity(): Entity?

    /**
     * @return a copy of the current feet position.
     */
    fun location(): Location

    /**
     * @return `false` once [remove] was called (or the owner plugin disabled, or the world unloaded).
     */
    fun isValid(): Boolean

    /** Removes the rig's entities for good. */
    fun remove()
}
