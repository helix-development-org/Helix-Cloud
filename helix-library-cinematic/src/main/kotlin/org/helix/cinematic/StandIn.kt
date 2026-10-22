package org.helix.cinematic

import de.tytoss.inpc.INpc
import de.tytoss.inpc.appearance.equipment.NpcEquipmentSlot
import de.tytoss.inpc.appearance.nametag.NameTagVisibility
import de.tytoss.inpc.npc.NpcDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * A look-alike NPC standing where the player stood while the player is the (invisible, spectating) camera: same
 * skin, same armor, the player's display name (with rank) above it. Everyone sees it, the player too – it is what
 * the camera films. Other plugins dress it up through [StandInSpawnEvent] (e.g. cosmetics) and clean up on
 * [StandInRemoveEvent]; things that follow the real player's position (a subtitle display) stay in place
 * anyway, because a spectating camera player does not move.
 *
 * @property player the player it stands in for.
 * @property location where it stands (the player's position and facing at the start).
 */
class StandIn internal constructor(
    private val plugin: Plugin,
    private val npcs: INpc,
    private val scope: CoroutineScope,
    val player: Player,
) {
    /** Where it stands. */
    val location: Location = player.location.clone()

    private var definition: NpcDefinition? = null
    private var nameLine: TextDisplay? = null
    private var removed = false

    /** The NPC's entity id once spawned (null before), e.g. for mounting decorations. */
    val entityId: Int?
        get() = definition?.let { def ->
            runCatching {
                val state = def.javaClass.getMethod("getState\$inpc").invoke(def)
                state.javaClass.getMethod("getEntityId").invoke(state) as Int
            }.getOrNull()
        }

    internal fun spawn() {
        val textures = player.playerProfile.properties.firstOrNull { it.name == "textures" }
        val armor = player.inventory
        nameLine = location.world.spawn(location.clone().add(0.0, NAME_HEIGHT, 0.0).apply { yaw = 0f; pitch = 0f }, TextDisplay::class.java) { line ->
            line.isPersistent = false
            line.billboard = Display.Billboard.CENTER
            line.text(player.displayName())
            line.isDefaultBackground = true
        }
        scope.launch {
            val def = npcs.npc("standin-${player.uniqueId}") {
                location(location)
                name(player.name.take(16))
                skin {
                    if (textures != null) value(textures.value, textures.signature) else name(player.name)
                }
                nameTag { visibility = NameTagVisibility.NEVER }
                equipment {
                    helmet = armor.helmet
                    chestplate = armor.chestplate
                    leggings = armor.leggings
                    boots = armor.boots
                    mainHand = armor.itemInMainHand
                    offHand = armor.itemInOffHand
                }
                visibility { tabListVisible = false }
            }
            if (removed) return@launch def.remove()
            def.spawn()
            definition = def
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!removed) plugin.server.pluginManager.callEvent(StandInSpawnEvent(this@StandIn))
            })
        }
    }

    /**
     * Puts [item] into one of the NPC's equipment slots (decorations, e.g. a cosmetic hat).
     *
     * @param slot the slot.
     * @param item the item.
     */
    fun equip(slot: NpcEquipmentSlot, item: ItemStack) {
        val def = definition ?: return
        scope.launch { def.handle.setItem(slot, item) }
    }

    internal fun remove() {
        if (removed) return
        removed = true
        nameLine?.remove()
        val def = definition
        definition = null
        plugin.server.pluginManager.callEvent(StandInRemoveEvent(this))
        if (def != null) scope.launch { def.remove() }
    }

    private companion object {
        /** Name line height above the feet – where the vanilla name tag sits, above a subtitle. */
        const val NAME_HEIGHT = 2.38
    }
}

/**
 * A [StandIn] was spawned – decorate it (main thread).
 *
 * @property standIn the stand-in.
 */
class StandInSpawnEvent(val standIn: StandIn) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    /** Bukkit handler list. */
    companion object {
        private val HANDLERS = HandlerList()

        /** @return the handler list (Bukkit convention). */
        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * A [StandIn] is being removed – remove decorations (main thread).
 *
 * @property standIn the stand-in.
 */
class StandInRemoveEvent(val standIn: StandIn) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    /** Bukkit handler list. */
    companion object {
        private val HANDLERS = HandlerList()

        /** @return the handler list (Bukkit convention). */
        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
