package de.tytoss.igui.screen

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.TooltipDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

/** Items that show a [ScreenSprite] (Bukkit side of the pure sprite layout). */
object SpriteItems {
    /**
     * @param namespace pack namespace of the sprite's item model.
     * @param sprite the placement.
     * @param tooltip tooltip lines (first = title); empty hides the tooltip entirely.
     * @return an item rendering the animated sprite.
     */
    fun of(namespace: String, sprite: ScreenSprite, tooltip: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(Material.PAPER)
        item.editMeta { meta ->
            meta.setItemModel(NamespacedKey(namespace, sprite.model))
            if (tooltip.isNotEmpty()) {
                meta.displayName(tooltip.first().decoration(TextDecoration.ITALIC, false))
                meta.lore(tooltip.drop(1).map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
        if (tooltip.isEmpty()) {
            item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build())
        }
        return item
    }
}
