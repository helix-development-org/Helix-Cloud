package org.helix.addons.phone.paper

import net.kyori.adventure.key.Key
import org.bukkit.Material

/**
 * Geometry and assets of the phone home screen. App tiles are real items in
 * chest slots (rows 1..4, columns 2..6) drawn on top of the phone case,
 * which is a title-glyph background — the same layout pattern the IGuard and
 * BetterMSGs GUIs use.
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

    /** The carrier item whose `CustomModelData` renders each app icon. */
    val CARRIER: Material = Material.matchMaterial("HEART_OF_THE_SEA") ?: Material.PAPER

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
}
