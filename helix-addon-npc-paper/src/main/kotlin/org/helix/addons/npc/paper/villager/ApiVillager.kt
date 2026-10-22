package org.helix.addons.npc.paper.villager

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.helix.addons.npc.paper.api.VillagerHandle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * A villager spawned by another plugin through the public API: a [VillagerRig] ticked and click-routed by Helix-NPC.
 *
 * @property owner plugin owning the villager.
 * @property key unique key of this villager (click debouncing).
 * @property rig the rig.
 */
class ApiVillager(val owner: Plugin, val key: String, val rig: VillagerRig) : VillagerHandle {
    private val callbacks = CopyOnWriteArrayList<Consumer<Player>>()
    private var removed = false

    /**
     * Delivers a click to the registered callbacks (exceptions are logged with the owner's logger).
     *
     * @param player the clicking player.
     */
    fun click(player: Player) {
        rig.raiseBrow()
        callbacks.forEach { callback ->
            runCatching { callback.accept(player) }
                .onFailure { owner.logger.warning("Villager click callback failed: ${it.message}") }
        }
    }

    override fun villagerId(): String = rig.character.id

    override fun say(text: String): Boolean = !removed && rig.say(text)

    override fun raiseBrow() = rig.raiseBrow()

    override fun cheer() = rig.cheer()

    override fun sulk() = rig.sulk()

    override fun sleep(asleep: Boolean) = rig.sleep(asleep)

    override fun teleport(location: Location) {
        if (!removed) rig.moveTo(location)
    }

    override fun location(): Location = rig.location.clone()

    override fun onClick(callback: Consumer<Player>) {
        callbacks += callback
    }

    override fun owns(entity: Entity): Boolean = !removed && rig.owns(entity)

    override fun isValid(): Boolean = !removed

    override fun remove() {
        removed = true
        callbacks.clear()
        rig.remove()
    }
}
