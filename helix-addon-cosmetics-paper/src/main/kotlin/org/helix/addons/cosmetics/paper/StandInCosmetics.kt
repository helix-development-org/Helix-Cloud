package org.helix.addons.cosmetics.paper

import de.tytoss.inpc.appearance.equipment.NpcEquipmentSlot
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.helix.cinematic.StandInRemoveEvent
import org.helix.cinematic.StandInSpawnEvent
import java.util.UUID

/**
 * Dresses cinematic stand-ins (look-alike NPCs of `helix-library-cinematic`, e.g. the AFK camera) in the player's
 * cosmetics: the headwear as NPC helmet, the wings as resting displays on its back. Registered only when
 * HelixCinematic is installed.
 *
 * @property cosmetics the headwear service.
 * @property wings the wings service, null without packetevents.
 */
class StandInCosmetics(
    private val cosmetics: CosmeticEquipmentService,
    private val wings: () -> PassengerWings?,
) : Listener {
    private val displays = HashMap<UUID, List<ItemDisplay>>()

    /** A stand-in appeared: put the cosmetics on it. */
    @EventHandler
    fun onSpawn(event: StandInSpawnEvent) {
        val standIn = event.standIn
        val uuid = standIn.player.uniqueId
        cosmetics.headwearItem(uuid)?.let { standIn.equip(NpcEquipmentSlot.HELMET, it) }
        val w = wings() ?: return
        w.wingsOf(uuid)?.let { name -> displays[uuid] = w.staticWings(name, standIn.location) }
    }

    /** A stand-in goes away: remove its wings. */
    @EventHandler
    fun onRemove(event: StandInRemoveEvent) {
        displays.remove(event.standIn.player.uniqueId)?.forEach { it.remove() }
    }

    /** Removes all stand-in wings (plugin disable). */
    fun shutdown() {
        displays.values.flatten().forEach { it.remove() }
        displays.clear()
    }
}
