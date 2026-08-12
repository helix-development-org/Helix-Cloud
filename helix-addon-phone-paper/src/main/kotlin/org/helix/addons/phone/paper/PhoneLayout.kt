package org.helix.addons.phone.paper

import net.kyori.adventure.key.Key

/**
 * Geometry and font naming of the phone home screen, shared by the renderer
 * and the click router. The app grid is aligned to chest slot rows 1..4 and
 * columns 2..6 so a drawn icon glyph sits exactly in its clickable slot.
 */
object PhoneLayout {
    /** App-grid columns (chest columns 2..6). */
    const val COLS = 5

    /** App-grid rows (chest rows 1..4). */
    const val ROWS = 4

    /** Maximum apps shown on the single home page. */
    const val CAPACITY = COLS * ROWS

    /** Close-button chest slot (top-right). */
    const val CLOSE_SLOT = 8

    /** Codepoint of the phone case glyph, matching the baked `gui` font. */
    const val CASE_CHAR = ""

    /** Font of the phone case glyph. */
    val CASE_FONT: Key = Key.key("helix_phone", "gui")

    /** Left pixel of chest column [col]. */
    fun columnX(col: Int): Int = 8 + col * 18

    /**
     * The chest slot for app-grid index [index] (row-major, 5 per row).
     *
     * @param index the app index (0-based).
     * @return the chest slot 0..53.
     */
    fun slotForIndex(index: Int): Int {
        val row = index / COLS + 1
        val col = index % COLS + 2
        return row * 9 + col
    }

    /**
     * The pixel X where app-grid index [index]'s icon is drawn.
     *
     * @param index the app index (0-based).
     * @return the pixel X from the GUI left.
     */
    fun iconX(index: Int): Int = columnX(index % COLS + 2)

    /**
     * The per-row icon font for a base font and app index.
     *
     * @param baseFont the base font key text, for example `helix_phone:icons`.
     * @param index the app index (0-based); its row selects the font variant.
     * @return the row-specific font key, for example `helix_phone:icons_row2`.
     */
    fun iconFont(baseFont: String, index: Int): Key {
        val namespace = baseFont.substringBefore(':', "helix_phone")
        val value = baseFont.substringAfter(':', baseFont)
        return Key.key(namespace, "${value}_row${index / COLS}")
    }
}
