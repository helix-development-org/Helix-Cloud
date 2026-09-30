@file:Suppress("UnstableApiUsage")

package org.helix.addons.cosmetics.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin

/**
 * The Blockbench wings (spread, flapping) as two item displays that ride the wearer **only on the clients**:
 * a faked `SET_PASSENGERS` packet (packetevents) mounts them, so every client positions them from the player's own
 * position each frame – walking, jumping, riding never lag. On the server they are no passengers (Paper refuses
 * cross-world teleports of players with passengers); they are just kept close to the wearer so they stay tracked.
 *
 * Passengers do not turn with their vehicle, so the body yaw and the flap are sent as transformation every tick
 * (interpolated on the client) – for other viewers in step with the player's own rotation updates.
 *
 * Every outgoing `SET_PASSENGERS` of a wearer gets the wing ids added (the server's own packets would otherwise
 * dismount them); the mount is resent when a viewer starts tracking the wearer or a wing, and periodically.
 */
class PassengerWings {
    private data class Pair(val name: String, val right: ItemDisplay, val left: ItemDisplay)

    private val worn = ConcurrentHashMap<UUID, Pair>()

    /** Wearer entity id → wing entity ids, read by the packet listener off the main thread. */
    private val mounts = ConcurrentHashMap<Int, IntArray>()
    private var listener: PacketListenerCommon? = null
    private var tick = 0L

    /** Registers the packet listener. */
    fun start() {
        listener = PacketEvents.getAPI().eventManager.registerListener(
            /** Adds the wing ids to every outgoing `SET_PASSENGERS` of a wearer. */
            object : PacketListener {
                override fun onPacketSend(event: PacketSendEvent) {
                    if (event.packetType != PacketType.Play.Server.SET_PASSENGERS) return
                    val packet = WrapperPlayServerSetPassengers(event)
                    val wings = mounts[packet.entityId] ?: return
                    val passengers = packet.passengers
                    if (wings.all { it in passengers }) return
                    packet.passengers = (passengers.toList() + wings.filterNot { it in passengers }).toIntArray()
                    event.markForReEncode(true)
                }
            },
            PacketListenerPriority.NORMAL,
        )
    }

    /**
     * Sets or clears [player]'s wings.
     *
     * @param player the wearer.
     * @param name the wings' model name (`wings_<id>`), or null for none.
     */
    fun update(player: Player, name: String?) {
        val current = worn[player.uniqueId]
        if (current?.name == name && current?.right?.isValid == true) return
        current?.let { despawn(player, it) }
        if (name == null) return
        val pair = Pair(name, spawn(player, "${name}_right"), spawn(player, "${name}_left"))
        worn[player.uniqueId] = pair
        mounts[player.entityId] = intArrayOf(pair.right.entityId, pair.left.entityId)
        mountLater(player)
    }

    /**
     * @param uuid a player.
     * @return whether the player wears wings.
     */
    fun wears(uuid: UUID): Boolean = worn.containsKey(uuid)

    /**
     * Resends the fake mount of [wearer] to [viewers].
     *
     * @param wearer the player wearing wings.
     * @param viewers who should see them mounted (default: everyone tracking the wearer and the wearer).
     */
    fun mount(wearer: Player, viewers: Collection<Player> = wearer.trackedBy + wearer) {
        val wings = mounts[wearer.entityId] ?: return
        val passengers = wearer.passengers.map { it.entityId } + wings.toList()
        val packet = WrapperPlayServerSetPassengers(wearer.entityId, passengers.toIntArray())
        viewers.forEach { if (it.isOnline) PacketEvents.getAPI().playerManager.sendPacket(it, packet) }
    }

    /**
     * Whether [entityId] is one of the wing displays (a viewer tracking it needs the mount).
     *
     * @param entityId an entity id.
     * @return the wearer of that wing, or null.
     */
    fun wearerOfWing(entityId: Int): UUID? =
        worn.entries.firstOrNull { it.value.right.entityId == entityId || it.value.left.entityId == entityId }?.key

    private var mountLater: (Player) -> Unit = {}

    /**
     * Sets how a delayed mount is scheduled (the plugin runs it two ticks later, after the spawn packets).
     *
     * @param scheduler schedules a mount of the given wearer.
     */
    fun scheduleMountsWith(scheduler: (Player) -> Unit) {
        mountLater = scheduler
    }

    /**
     * Updates orientation, flap and visibility, keeps the displays near their wearer and in the wearer's world;
     * called every tick.
     *
     * @param onlinePlayers currently connected players.
     */
    fun tick(onlinePlayers: Collection<Player>) {
        tick++
        val byUuid = onlinePlayers.associateBy { it.uniqueId }
        worn.forEach { (uuid, pair) ->
            val player = byUuid[uuid] ?: return@forEach
            if (!pair.right.isValid || !pair.left.isValid || pair.right.world != player.world) {
                despawn(player, pair)
                update(player, pair.name)
                return@forEach
            }
            if (tick % KEEP_CLOSE_TICKS == 0L && pair.right.location.distanceSquared(player.location) > KEEP_CLOSE_DISTANCE_SQ) {
                pair.right.teleport(anchor(player))
                pair.left.teleport(anchor(player))
            }
            val show = visible(player)
            val flap = flapDegrees(player, tick)
            apply(pair.right, if (show) wingTransform(player, -flap) else HIDDEN)
            apply(pair.left, if (show) wingTransform(player, flap) else HIDDEN)
            if (tick % REMOUNT_TICKS == 0L) mount(player)
        }
    }

    /**
     * Removes a player's wings; called on quit.
     *
     * @param player the player.
     */
    fun remove(player: Player) {
        worn[player.uniqueId]?.let { despawn(player, it) }
    }

    /**
     * Removes all wings and the packet listener; called on plugin disable.
     *
     * @param onlinePlayers currently connected players.
     */
    fun shutdown(onlinePlayers: Collection<Player>) {
        onlinePlayers.forEach { remove(it) }
        worn.values.forEach { it.right.remove(); it.left.remove() }
        worn.clear()
        mounts.clear()
        listener?.let { PacketEvents.getAPI().eventManager.unregisterListener(it) }
        listener = null
    }

    private fun despawn(player: Player, pair: Pair) {
        worn.remove(player.uniqueId)
        mounts.remove(player.entityId)
        pair.right.remove()
        pair.left.remove()
    }

    private fun spawn(player: Player, model: String): ItemDisplay =
        player.world.spawn(anchor(player), ItemDisplay::class.java) { entity ->
            entity.billboard = Display.Billboard.FIXED
            entity.isPersistent = false
            entity.transformation = HIDDEN
            entity.setItemStack(ItemStack.of(Material.PAPER).apply { setData(DataComponentTypes.ITEM_MODEL, Key.key("helix_cosmetics", model)) })
        }

    /**
     * Server-side position of the wing displays: at the player, but always yaw/pitch 0 – an entity's own
     * rotation is applied on top of the transformation, so taking the player's facing would turn the wings twice.
     */
    private fun anchor(player: Player) = player.location.clone().apply { yaw = 0f; pitch = 0f }

    private fun apply(display: ItemDisplay, transformation: Transformation) {
        if (display.transformation == transformation) return
        display.interpolationDelay = 0
        display.interpolationDuration = SMOOTH_TICKS
        display.transformation = transformation
    }

    private fun visible(player: Player): Boolean =
        player.gameMode != GameMode.SPECTATOR && !player.isInvisible && player.pose in SHOWN_POSES

    /** One wing hinged at the spine behind the back; [flap] is the signed sweep angle in degrees. */
    private fun wingTransform(player: Player, flap: Float): Transformation {
        val bodyYaw = Math.toRadians(-player.bodyYaw.toDouble()).toFloat()
        val back = Vector3f(0f, NECK_BELOW_SEAT + BACK_DROP, -BACK_OFFSET).rotateY(bodyYaw)
        val rotation = Quaternionf().rotateY(bodyYaw).rotateY(Math.toRadians(flap.toDouble()).toFloat())
        return Transformation(back, rotation, Vector3f(WING_SCALE, WING_SCALE, WING_SCALE), Quaternionf())
    }

    private companion object {
        /**
         * The neck relative to the client-side passenger seat (top of the player's hitbox): 1.8 → 1.5 standing,
         * 1.5 → 1.2 sneaking – the same offset.
         */
        const val NECK_BELOW_SEAT = -0.3f

        /** Wing hinge: behind the spine and slightly below the neck (blocks). */
        const val BACK_OFFSET = 0.16f
        const val BACK_DROP = -0.06f
        const val WING_SCALE = 1.1f

        /** Client-side interpolation of the orientation (sent every tick). */
        const val SMOOTH_TICKS = 1

        /**
         * Server-side reposition to keep the displays tracked – only when they drifted off, the client places
         * mounted displays itself and every teleport packet is a chance for a visible hiccup.
         */
        const val KEEP_CLOSE_TICKS = 10L
        const val KEEP_CLOSE_DISTANCE_SQ = 16.0
        const val REMOUNT_TICKS = 40L

        val SHOWN_POSES = setOf(Pose.STANDING, Pose.SNEAKING)

        val HIDDEN = Transformation(Vector3f(), Quaternionf(), Vector3f(0f, 0f, 0f), Quaternionf())

        /** Sweep-back angle: resting flap, faster and wider while flying or gliding. */
        fun flapDegrees(player: Player, tick: Long): Float {
            val airborne = player.isFlying || player.isGliding
            val period = if (airborne) 14.0 else 50.0
            val amplitude = if (airborne) 18.0 else 7.0
            val base = if (airborne) 18.0 else 26.0
            val phase = (tick + (player.uniqueId.hashCode() and 63)) * 2 * Math.PI / period
            return (base + amplitude * sin(phase)).toFloat()
        }
    }
}
