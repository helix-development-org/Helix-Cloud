package org.helix.addons.cosmetics.paper

import org.bukkit.GameMode
import org.bukkit.Location
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
 * Owns the item display entities rendering players' equipped cosmetics.
 *
 * Each cosmetic is a `CustomModelData`-tagged [Material.PAPER] item shown by an [ItemDisplay] entity, never
 * equipped in a real armor/elytra slot. All displays sit at the player's neck – the models (Blockbench,
 * 1 model pixel = 1 player-skin pixel) are built around that pivot:
 *
 *  - headwear turns with the head (yaw and pitch), like a real hat
 *  - wings are two displays (right and left, the left one at `CustomModelData` + [WING_LEFT_OFFSET]) hinged at
 *    the spine behind the back, turning with the body; they flap gently, faster while flying or gliding
 *
 * The displays are re-teleported and re-transformed every tick (not attached as passengers, so riding real
 * vehicles keeps working); teleport and transformation interpolation smooth the motion on the client.
 * Poses without a sensible attachment (swimming, gliding flat, sleeping) and spectators hide the cosmetics.
 */
class CosmeticDisplayService {
    private val wings = ConcurrentHashMap<UUID, Pair<ItemDisplay, ItemDisplay>>()
    private val headwear = ConcurrentHashMap<UUID, ItemDisplay>()
    private var tick = 0L

    /**
     * Ensures [player] shows [customModelData] as their wings, or removes the displays when `null`.
     *
     * @param player the wearer.
     * @param customModelData the right wing's `CustomModelData`, or null for none.
     */
    fun updateWings(player: Player, customModelData: Int?) {
        if (customModelData == null) {
            wings.remove(player.uniqueId)?.let { (r, l) -> r.remove(); l.remove() }
            return
        }
        val pair = wings[player.uniqueId]?.takeIf { it.first.isValid && it.second.isValid }
            ?: (spawn(player) to spawn(player)).also { wings[player.uniqueId] = it }
        setModel(pair.first, customModelData)
        setModel(pair.second, customModelData + WING_LEFT_OFFSET)
    }

    /**
     * Ensures [player] shows [customModelData] as their headwear, or removes the display when `null`.
     *
     * @param player the wearer.
     * @param customModelData the headwear's `CustomModelData`, or null for none.
     */
    fun updateHeadwear(player: Player, customModelData: Int?) {
        if (customModelData == null) {
            headwear.remove(player.uniqueId)?.remove()
            return
        }
        val display = headwear[player.uniqueId]?.takeIf { it.isValid } ?: spawn(player).also { headwear[player.uniqueId] = it }
        setModel(display, customModelData)
    }

    /**
     * Moves every tracked display to its player's neck and updates orientation and wing flap; called every tick.
     *
     * @param onlinePlayers currently connected players, to look up by uuid.
     */
    fun track(onlinePlayers: Collection<Player>) {
        tick++
        val byUuid = onlinePlayers.associateBy { it.uniqueId }
        headwear.forEach { (uuid, display) ->
            if (!display.isValid) return@forEach run { headwear.remove(uuid) }
            val player = byUuid[uuid] ?: return@forEach
            display.teleport(neck(player))
            apply(display, if (visible(player)) headTransform(player) else HIDDEN)
        }
        wings.forEach { (uuid, pair) ->
            if (!pair.first.isValid || !pair.second.isValid) return@forEach run { wings.remove(uuid) }
            val player = byUuid[uuid] ?: return@forEach
            val show = visible(player)
            val flap = flapDegrees(player, tick)
            listOf(pair.first to -1f, pair.second to 1f).forEach { (display, side) ->
                display.teleport(neck(player))
                apply(display, if (show) wingTransform(player, side * flap) else HIDDEN)
            }
        }
    }

    /**
     * Removes all of a player's cosmetic displays; called on quit.
     *
     * @param uuid the player's uuid.
     */
    fun remove(uuid: UUID) {
        wings.remove(uuid)?.let { (r, l) -> r.remove(); l.remove() }
        headwear.remove(uuid)?.remove()
    }

    /** Removes every tracked display; called on plugin disable. */
    fun shutdown() {
        wings.keys.toList().forEach { remove(it) }
        headwear.keys.toList().forEach { remove(it) }
    }

    private fun setModel(display: ItemDisplay, customModelData: Int) {
        val current = display.itemStack?.itemMeta?.takeIf { it.hasCustomModelData() }?.customModelData
        if (current != customModelData) {
            display.setItemStack(ItemStack(Material.PAPER).apply { editMeta { it.setCustomModelData(customModelData) } })
        }
    }

    private fun spawn(player: Player): ItemDisplay =
        player.world.spawn(neck(player), ItemDisplay::class.java) { entity ->
            // FIXED: orientation comes entirely from the transformation, not from billboarding toward viewers
            entity.billboard = Display.Billboard.FIXED
            entity.isPersistent = false
            entity.teleportDuration = SMOOTH_TICKS
            entity.transformation = HIDDEN
        }

    private fun apply(display: ItemDisplay, transformation: Transformation) {
        if (display.transformation == transformation) return
        display.interpolationDelay = 0
        display.interpolationDuration = SMOOTH_TICKS
        display.transformation = transformation
    }

    private fun visible(player: Player): Boolean =
        player.gameMode != GameMode.SPECTATOR && !player.isInvisible && player.pose in SHOWN_POSES

    /** The neck: pivot of every cosmetic model (model centre). */
    private fun neck(player: Player): Location {
        val height = if (player.isSneaking) NECK_SNEAKING else NECK_STANDING
        return player.location.clone().apply { yaw = 0f; pitch = 0f }.add(0.0, height, 0.0)
    }

    private fun headTransform(player: Player): Transformation {
        val rotation = Quaternionf()
            .rotateY(Math.toRadians(-player.location.yaw.toDouble()).toFloat())
            .rotateX(Math.toRadians(player.location.pitch.toDouble()).toFloat())
        return Transformation(Vector3f(), rotation, Vector3f(1f, 1f, 1f), Quaternionf())
    }

    /** One wing hinged at the spine behind the back; [flap] is the signed sweep angle in degrees. */
    private fun wingTransform(player: Player, flap: Float): Transformation {
        val bodyYaw = Math.toRadians(-player.bodyYaw.toDouble()).toFloat()
        val back = Vector3f(0f, BACK_DROP, -BACK_OFFSET).rotateY(bodyYaw)
        val rotation = Quaternionf().rotateY(bodyYaw).rotateY(Math.toRadians(flap.toDouble()).toFloat())
        return Transformation(back, rotation, Vector3f(WING_SCALE, WING_SCALE, WING_SCALE), Quaternionf())
    }

    private companion object {
        /** Offset of the left wing's `CustomModelData` (mirrors the pack generator). */
        const val WING_LEFT_OFFSET = 50

        /** Neck height above the feet (standing / sneaking). */
        const val NECK_STANDING = 1.5
        const val NECK_SNEAKING = 1.2

        /** Wing hinge: behind the spine and slightly below the neck (blocks). */
        const val BACK_OFFSET = 0.16f
        const val BACK_DROP = -0.06f
        const val WING_SCALE = 1.1f

        /** Client-side interpolation of movement and transformation. */
        const val SMOOTH_TICKS = 2

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
