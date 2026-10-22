package org.helix.cinematic

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * The camera of one viewer: an invisible item display only the viewer tracks, made the viewer's camera with the
 * vanilla camera packet (packetevents). The viewer keeps their game mode, so their own body, name tag (with rank),
 * subtitle and cosmetics are in the picture – and because the camera is no player the client hides hotbar, hearts
 * and the hand. Movement is sent as interpolated teleports ([STEP_TICKS]), the client smooths in between.
 *
 * @property plugin owner of the entity visibility.
 * @property viewer the player looking through it.
 */
internal class CameraRig(private val plugin: Plugin, private val viewer: Player) {
    private val display: ItemDisplay = viewer.world.spawn(viewer.location, ItemDisplay::class.java) { d ->
        d.isPersistent = false
        d.isVisibleByDefault = false
        d.billboard = Display.Billboard.FIXED
        d.teleportDuration = STEP_TICKS
    }.also { viewer.showEntity(plugin, it) }

    private var ticksAlive = 0
    private var active = false
    private var restoreSmoothing = false

    /** Whether the rig still exists in the viewer's world. */
    val valid: Boolean get() = display.isValid && display.world == viewer.world

    /** Whether the client knows the entity (it was spawned a few ticks ago) – the camera packet needs that. */
    val ready: Boolean get() = ticksAlive >= READY_TICKS

    /** Called every tick. */
    fun tick() {
        ticksAlive++
        if (restoreSmoothing) {
            display.teleportDuration = STEP_TICKS
            restoreSmoothing = false
        }
    }

    /**
     * Glides to a pose (interpolated over [STEP_TICKS]).
     *
     * @param position camera position.
     * @param rotation view direction.
     */
    fun glide(position: Vec3, rotation: Rotation) {
        display.teleport(position.toLocation(viewer.world, rotation))
    }

    /**
     * Jumps to a pose without interpolation (cuts).
     *
     * @param position camera position.
     * @param rotation view direction.
     */
    fun jump(position: Vec3, rotation: Rotation) {
        display.teleportDuration = 0
        display.teleport(position.toLocation(viewer.world, rotation))
        restoreSmoothing = true
    }

    /** Makes the rig the viewer's camera. */
    fun activate() {
        if (active) return
        send(display.entityId)
        active = true
    }

    /** Gives the viewer their own view back and removes the entity. */
    fun release() {
        if (active && viewer.isOnline) send(viewer.entityId)
        active = false
        display.remove()
    }

    private fun send(entityId: Int) = PacketEvents.getAPI().playerManager.sendPacket(viewer, WrapperPlayServerCamera(entityId))

    /** Timing constants. */
    companion object {
        /** Camera update interval; the client interpolates teleports over the same time. */
        const val STEP_TICKS = 2

        /** Ticks after spawning before the client surely tracks the entity. */
        const val READY_TICKS = 3
    }
}
