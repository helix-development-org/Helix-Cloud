package de.tytoss.igui.display

import net.kyori.adventure.text.Component

/**
 * The result of [de.tytoss.igui.display.DisplayBuilder.build]: a rendered
 * title component ready to pass to `Bukkit.createInventory`, plus where its
 * cursor ended up.
 *
 * @property component the assembled title, made of texture and text glyphs
 *  positioned via invisible spacing glyphs.
 * @property finalCursorPixels the cursor position, in pixels, after the last
 *  appended element — useful for chaining further layout decisions.
 */
class GuiTitle internal constructor(
    val component: Component,
    val finalCursorPixels: Int,
)
