package org.helix.addons.lobby.paper

import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Builds lobby hotbar [ItemStack]s and tags them so a later click can be
 * routed back to its configured action without depending on the slot (which
 * a player could, in principle, shuffle before protection kicks in).
 *
 * @param plugin the owning plugin, for the persistent-data keys.
 */
class LobbyItemFactory(plugin: Plugin) {
    private val mini = MiniMessage.miniMessage()
    private val markerKey = NamespacedKey(plugin, "lobby_item")
    private val actionKey = NamespacedKey(plugin, "action")
    private val commandKey = NamespacedKey(plugin, "command")

    /**
     * Builds a tagged item for a configured lobby entry.
     *
     * @param item the configuration entry.
     * @return the ready-to-place item; an unknown material falls back to a
     *  barrier so the misconfiguration is obvious in game.
     */
    fun build(item: LobbyItem): ItemStack {
        val material = Material.matchMaterial(item.material) ?: Material.BARRIER
        return ItemStack(material).apply {
            editMeta { meta ->
                if (item.name.isNotBlank()) {
                    meta.displayName(mini.deserialize(item.name).decoration(TextDecoration.ITALIC, false))
                }
                if (item.lore.isNotEmpty()) {
                    meta.lore(item.lore.map { mini.deserialize(it).decoration(TextDecoration.ITALIC, false) })
                }
                if (item.glow) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true)
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                }
                meta.persistentDataContainer.set(markerKey, PersistentDataType.BYTE, 1)
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, item.action.name)
                meta.persistentDataContainer.set(commandKey, PersistentDataType.STRING, item.command)
            }
        }
    }

    /**
     * Reads the action a tagged lobby item should perform.
     *
     * @param item the clicked item (may be `null` or untagged).
     * @return the action and its command, or `null` when the item is not a
     *  lobby item.
     */
    fun actionOf(item: ItemStack?): Pair<ItemAction, String>? {
        val meta = item?.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        if (pdc.get(markerKey, PersistentDataType.BYTE) != (1).toByte()) return null
        val action = pdc.get(actionKey, PersistentDataType.STRING)
            ?.let { runCatching { ItemAction.valueOf(it) }.getOrNull() }
            ?: ItemAction.RUN_COMMAND
        val command = pdc.get(commandKey, PersistentDataType.STRING).orEmpty()
        return action to command
    }

    /**
     * Whether an item is a tagged lobby item (used to protect it from being
     * moved out of the hotbar).
     *
     * @param item the item to test.
     * @return `true` when the item carries the lobby marker.
     */
    fun isLobbyItem(item: ItemStack?): Boolean {
        val pdc = item?.itemMeta?.persistentDataContainer ?: return false
        return pdc.get(markerKey, PersistentDataType.BYTE) == (1).toByte()
    }
}
