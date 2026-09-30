@file:Suppress("UnstableApiUsage")

package org.helix.addons.cosmetics.paper

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Equippable
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows players' cosmetics as **fake equipment**: the client renders them as part of the player model, so they
 * follow every movement, turn and animation exactly (no entity lagging behind).
 *
 *  - headwear: a helmet-slot item with the cosmetic's item model – the model's `head` display puts it on the head
 *  - wings: a chest-slot item with an `equippable` asset whose `wings` layer draws them on the elytra model
 *
 * Only packets are faked ([Player.sendEquipmentChange]); the real inventory is untouched. Viewers are everyone
 * tracking the wearer plus the wearer (whose client then shows it in F5 and in its armor slot). The real
 * equipment is resent by the server on armor changes and when a viewer starts tracking – the plugin reapplies the
 * cosmetic right after ([refresh]) and periodically. While a cosmetic is worn it wins over real armor in that slot
 * (cosmetics are meant for lobbies, not for game servers with armor).
 */
class CosmeticEquipmentService {
    private val wings = ConcurrentHashMap<UUID, String>()
    private val headwear = ConcurrentHashMap<UUID, String>()

    /**
     * Sets or clears [player]'s wings.
     *
     * @param player the wearer.
     * @param customModelData the wings' `CustomModelData`, or null for none.
     */
    fun updateWings(player: Player, customModelData: Int?) =
        update(wings, player, customModelData?.let(CosmeticModels::wings), EquipmentSlot.CHEST)

    /**
     * Sets or clears [player]'s headwear.
     *
     * @param player the wearer.
     * @param customModelData the headwear's `CustomModelData`, or null for none.
     */
    fun updateHeadwear(player: Player, customModelData: Int?) =
        update(headwear, player, customModelData?.let(CosmeticModels::headwear), EquipmentSlot.HEAD)

    /**
     * Sends [wearer]'s cosmetics to [viewers] (default: everyone tracking the wearer and the wearer).
     *
     * @param wearer the player wearing cosmetics.
     * @param viewers who should see them.
     */
    fun refresh(wearer: Player, viewers: Collection<Player> = viewersOf(wearer)) {
        val items = cosmeticItems(wearer)
        if (items.isEmpty()) return
        viewers.forEach { if (it.isOnline) it.sendEquipmentChange(wearer, items) }
    }

    /**
     * Reapplies every wearer's cosmetics to all their viewers; called periodically.
     *
     * @param onlinePlayers currently connected players.
     */
    fun refreshAll(onlinePlayers: Collection<Player>) {
        onlinePlayers.forEach { if (wears(it.uniqueId)) refresh(it) }
    }

    /**
     * @param uuid a player.
     * @return whether the player wears any cosmetic.
     */
    fun wears(uuid: UUID): Boolean = wings.containsKey(uuid) || headwear.containsKey(uuid)

    /**
     * Forgets a player's cosmetics; called on quit.
     *
     * @param uuid the player's uuid.
     */
    fun remove(uuid: UUID) {
        wings.remove(uuid)
        headwear.remove(uuid)
    }

    /**
     * Shows everyone's real equipment again; called on plugin disable.
     *
     * @param onlinePlayers currently connected players.
     */
    fun shutdown(onlinePlayers: Collection<Player>) {
        onlinePlayers.filter { wears(it.uniqueId) }.forEach { wearer ->
            val real = mapOf(
                EquipmentSlot.HEAD to (wearer.inventory.helmet ?: ItemStack.empty()),
                EquipmentSlot.CHEST to (wearer.inventory.chestplate ?: ItemStack.empty()),
            )
            viewersOf(wearer).forEach { it.sendEquipmentChange(wearer, real) }
        }
        wings.clear()
        headwear.clear()
    }

    private fun update(map: ConcurrentHashMap<UUID, String>, player: Player, name: String?, slot: EquipmentSlot) {
        val previous = if (name == null) map.remove(player.uniqueId) else map.put(player.uniqueId, name)
        if (previous == name) return
        if (name == null) {
            // Cosmetic abgelegt: echtes Teil wieder zeigen
            val real = (if (slot == EquipmentSlot.HEAD) player.inventory.helmet else player.inventory.chestplate) ?: ItemStack.empty()
            viewersOf(player).forEach { it.sendEquipmentChange(player, slot, real) }
        } else {
            refresh(player)
        }
    }

    private fun cosmeticItems(wearer: Player): Map<EquipmentSlot, ItemStack> = buildMap {
        headwear[wearer.uniqueId]?.let { put(EquipmentSlot.HEAD, headItem(it)) }
        wings[wearer.uniqueId]?.let { put(EquipmentSlot.CHEST, wingsItem(it)) }
    }

    private fun viewersOf(wearer: Player): Collection<Player> = wearer.trackedBy + wearer

    private companion object {
        const val NAMESPACE = "helix_cosmetics"

        /** Helmet-slot item showing the headwear model [name]. */
        fun headItem(name: String): ItemStack = ItemStack.of(Material.PAPER).apply {
            setData(DataComponentTypes.ITEM_MODEL, Key.key(NAMESPACE, name))
        }

        /** Chest-slot item whose equipment asset [name] draws the wings on the elytra layer. */
        fun wingsItem(name: String): ItemStack = ItemStack.of(Material.PAPER).apply {
            setData(DataComponentTypes.ITEM_MODEL, Key.key(NAMESPACE, "${name}_icon"))
            setData(
                DataComponentTypes.EQUIPPABLE,
                Equippable.equippable(EquipmentSlot.CHEST).assetId(Key.key(NAMESPACE, name)).build(),
            )
        }
    }
}
