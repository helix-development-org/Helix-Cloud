package org.helix.addons.cosmetics.paper

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * One player's two cosmetic slots, tracked independently.
 *
 * @property yOffset blocks above the player's feet the display sits at.
 * @property scale uniform scale applied to the model.
 */
private enum class Slot(val yOffset: Double, val scale: Float) {
    /** Worn just above the head. */
    HEADWEAR(1.9, 0.8f),

    /** Worn at back height. */
    WINGS(1.2, 1.0f),
}

/**
 * Owns the item display entities rendering players' equipped cosmetics.
 *
 * Each cosmetic is a `CustomModelData`-tagged [Material.PAPER] item shown
 * by an [ItemDisplay] entity, never actually equipped in a real armor/
 * elytra slot — re-teleported and re-rotated every tick to track the
 * player's position and facing, rather than attached as a passenger, so
 * it never interferes with the player riding a real vehicle (the same
 * reason `helix-addon-subtitles-paper`'s subtitle text works this way).
 */
class CosmeticDisplayService {
    private val wings = ConcurrentHashMap<UUID, ItemDisplay>()
    private val headwear = ConcurrentHashMap<UUID, ItemDisplay>()

    /**
     * Ensures [player] shows [customModelData] as their wings, or removes
     * the display when `null`.
     */
    fun updateWings(player: Player, customModelData: Int?) = update(wings, player, Slot.WINGS, customModelData)

    /**
     * Ensures [player] shows [customModelData] as their headwear, or
     * removes the display when `null`.
     */
    fun updateHeadwear(player: Player, customModelData: Int?) = update(headwear, player, Slot.HEADWEAR, customModelData)

    /**
     * Re-teleports and re-rotates every tracked display to its player's
     * current position and facing; called every tick.
     *
     * @param onlinePlayers currently connected players, to look up by uuid.
     */
    fun track(onlinePlayers: Collection<Player>) {
        val byUuid = onlinePlayers.associateBy { it.uniqueId }
        trackSlot(wings, byUuid, Slot.WINGS)
        trackSlot(headwear, byUuid, Slot.HEADWEAR)
    }

    /**
     * Removes both of a player's cosmetic displays; called on quit.
     *
     * @param uuid the player's uuid.
     */
    fun remove(uuid: UUID) {
        wings.remove(uuid)?.remove()
        headwear.remove(uuid)?.remove()
    }

    /** Removes every tracked display; called on plugin disable. */
    fun shutdown() {
        wings.keys.toList().forEach { wings.remove(it)?.remove() }
        headwear.keys.toList().forEach { headwear.remove(it)?.remove() }
    }

    private fun update(map: ConcurrentHashMap<UUID, ItemDisplay>, player: Player, slot: Slot, customModelData: Int?) {
        if (customModelData == null) {
            map.remove(player.uniqueId)?.remove()
            return
        }
        val display = map[player.uniqueId]?.takeIf { it.isValid } ?: spawn(player, slot).also { map[player.uniqueId] = it }
        val currentCmd = display.itemStack?.itemMeta?.takeIf { it.hasCustomModelData() }?.customModelData
        if (currentCmd != customModelData) {
            display.setItemStack(
                ItemStack(Material.PAPER).apply { editMeta { meta -> meta.setCustomModelData(customModelData) } },
            )
        }
    }

    private fun spawn(player: Player, slot: Slot): ItemDisplay =
        player.world.spawn(location(player, slot), ItemDisplay::class.java) { entity ->
            // FIXED: the plugin controls orientation entirely via transformation() below, rather
            // than letting the entity billboard toward each viewer like a name tag would.
            entity.billboard = Display.Billboard.FIXED
            entity.isPersistent = false
            entity.transformation = transformationFor(player, slot)
        }

    private fun trackSlot(map: ConcurrentHashMap<UUID, ItemDisplay>, byUuid: Map<UUID, Player>, slot: Slot) {
        map.forEach { (uuid, display) ->
            if (!display.isValid) {
                map.remove(uuid)
                return@forEach
            }
            val player = byUuid[uuid] ?: return@forEach
            display.teleport(location(player, slot))
            display.transformation = transformationFor(player, slot)
        }
    }

    private fun location(player: Player, slot: Slot): Location = player.location.clone().add(0.0, slot.yOffset, 0.0)

    /** Scales the model and rotates it to match the player's yaw, so it sits correctly regardless of facing. */
    private fun transformationFor(player: Player, slot: Slot): Transformation {
        val yawRadians = Math.toRadians(-player.location.yaw.toDouble()).toFloat()
        return Transformation(
            Vector3f(0f, 0f, 0f),
            Quaternionf().rotateY(yawRadians),
            Vector3f(slot.scale, slot.scale, slot.scale),
            Quaternionf(),
        )
    }
}
