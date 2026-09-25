package de.tytoss.igui.internal

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Borrows a player's storage contents (hotbar + main inventory) while a
 * [de.tytoss.igui.gui.GuiDefinitionBuilder.playerInventory] GUI is open.
 *
 * The snapshot lives in memory and in the player's persistent data container
 * at the same time as the inventory is cleared, so both end up in the same
 * player-data save: after a crash the next join restores the snapshot
 * ([restoreCrashed]) instead of losing items. Armour and off-hand are never
 * touched. Items rendered by the GUI are tagged with [guiItemKey] so death
 * drops can tell them apart from real items.
 *
 * @param plugin owner of the persistent-data keys.
 */
internal class PlayerInventoryTakeover(plugin: JavaPlugin) {
    private val snapshotKey = NamespacedKey(plugin, "igui_inventory_snapshot")

    /** Persistent-data key marking items that only exist inside a GUI. */
    val guiItemKey = NamespacedKey(plugin, "igui_gui_item")

    private val saved = HashMap<UUID, Array<ItemStack?>>()

    /**
     * Whether [player] currently has their inventory borrowed.
     *
     * @param player the player.
     * @return `true` while a takeover is active.
     */
    fun isActive(player: Player): Boolean = player.uniqueId in saved

    /**
     * Snapshots and clears the storage contents, once per takeover.
     *
     * @param player the player opening a full-window GUI.
     */
    fun begin(player: Player) {
        if (player.uniqueId in saved) return
        val contents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        saved[player.uniqueId] = contents
        player.persistentDataContainer.set(snapshotKey, PersistentDataType.BYTE_ARRAY, serialize(contents))
        player.inventory.storageContents = arrayOfNulls(contents.size)
    }

    /**
     * Writes one GUI item into a storage slot, tagged as GUI-only.
     *
     * @param player the viewer.
     * @param storageIndex index into `storageContents` (0..8 hotbar, 9..35 main).
     * @param item the item to show, or `null` for an empty slot.
     */
    fun show(player: Player, storageIndex: Int, item: ItemStack?) {
        val tagged = item?.takeIf { !it.isEmpty }?.clone()?.apply {
            editMeta { meta -> meta.persistentDataContainer.set(guiItemKey, PersistentDataType.BYTE, 1) }
        }
        player.inventory.setItem(storageIndex, tagged)
    }

    /**
     * Restores the snapshot and ends the takeover; a no-op without one.
     *
     * @param player the player.
     */
    fun restore(player: Player) {
        val contents = saved.remove(player.uniqueId) ?: return
        player.inventory.storageContents = contents
        player.persistentDataContainer.remove(snapshotKey)
    }

    /**
     * Ends the takeover without restoring, handing the real items to the
     * caller (used on death, where they become drops).
     *
     * @param player the player.
     * @return the snapshotted items, or `null` without an active takeover.
     */
    fun release(player: Player): List<ItemStack> {
        val contents = saved.remove(player.uniqueId) ?: return emptyList()
        player.persistentDataContainer.remove(snapshotKey)
        return contents.filterNotNull().filter { !it.isEmpty }
    }

    /**
     * Whether [item] was rendered by a GUI (and must never leave it).
     *
     * @param item the item to check.
     * @return `true` for tagged GUI items.
     */
    fun isGuiItem(item: ItemStack?): Boolean =
        item?.takeIf { !it.isEmpty }?.persistentDataContainer?.has(guiItemKey, PersistentDataType.BYTE) == true

    /**
     * Restores a snapshot left behind by a crash, if the player has one and
     * no takeover is active.
     *
     * @param player the joining player.
     */
    fun restoreCrashed(player: Player) {
        if (player.uniqueId in saved) return
        val bytes = player.persistentDataContainer.get(snapshotKey, PersistentDataType.BYTE_ARRAY) ?: return
        runCatching { player.inventory.storageContents = deserialize(bytes) }
        player.persistentDataContainer.remove(snapshotKey)
    }

    /**
     * Restores every active takeover (plugin shutdown).
     *
     * @param resolver resolves online players by uuid.
     */
    fun restoreAll(resolver: (UUID) -> Player?) {
        saved.keys.toList().forEach { uuid -> resolver(uuid)?.let(::restore) }
    }

    private fun serialize(contents: Array<ItemStack?>): ByteArray =
        ItemStack.serializeItemsAsBytes(contents.map { it ?: ItemStack.empty() })

    private fun deserialize(bytes: ByteArray): Array<ItemStack?> =
        ItemStack.deserializeItemsFromBytes(bytes).map { stack -> stack.takeIf { !it.isEmpty } }.toTypedArray()
}
