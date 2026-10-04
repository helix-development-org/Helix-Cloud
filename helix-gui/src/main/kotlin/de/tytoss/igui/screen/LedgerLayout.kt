package de.tytoss.igui.screen

/**
 * Layout of the shared **ledger** screen in container pixels (pure; shared with the Helix-GUIs pack generator):
 * a 384×256 medieval board (wood wall, iron frame) with a long parchment scroll over the slot grid – a ribbon for
 * the title, eight list rows (grid rows 1..8), a footer with page arrows and up to two wooden buttons (grid row 9)
 * – and a pinned note on a board left and right for extra text.
 */
object LedgerLayout {
    /** Font of the ledger glyphs (`helix_guis:ledger`). */
    const val FONT = "helix_guis:ledger"

    /** Board image size and position (same frame as other screens). */
    const val BG_W = 384
    const val BG_H = 256
    const val BG_X = -104
    const val BG_Y = -17

    /** Glyphs: board tiles, arrows, button. */
    const val BG_TILE = 0xE000
    const val ARROW_LEFT = 0xE010
    const val ARROW_RIGHT = 0xE011
    const val BUTTON = 0xE012
    const val ARROW_SIZE = 16
    const val BUTTON_W = 52
    const val BUTTON_H = 16

    /** Rows per page and their grid rows. */
    const val ROWS = 8
    const val FIRST_ROW = 1
    const val FOOTER_ROW = 9

    /** Footer: arrows at grid column 0 / 8, buttons over columns 1..3 and 5..7, page text at column 4. */
    const val FOOTER_TOP = 198
    const val ARROW_LEFT_X = 8
    const val ARROW_RIGHT_X = 152
    val BUTTON_X = intArrayOf(27, 99)
    val BUTTON_COLUMNS = arrayOf(1..3, 5..7)
    const val PAGE_COLUMN = 4

    /** Title on the ribbon, subtitle under it (grid row 0). */
    const val TITLE_TOP = 6
    const val SUBTITLE_TOP = 23
    const val CENTER_X = 88

    /** Row texts: label from the left (after an icon in column 0), value right-aligned. */
    const val LABEL_X = 28
    const val LABEL_X_NO_ICON = 12
    const val VALUE_RIGHT = 165
    const val ROW_TEXT_OFFSET = 5

    /** Side notes: centre x, first line top, line height, line count, width. */
    const val LEFT_X = -50
    const val RIGHT_X = 226
    const val NOTE_TOP = 30
    const val NOTE_LINE = 11
    const val NOTE_LINES = 15
    const val NOTE_WIDTH = 66

    /**
     * @param index row 0..7.
     * @return the row's top edge (the slot grid has gaps between chest and inventory rows).
     */
    fun rowTop(index: Int): Int {
        val gridRow = FIRST_ROW + index
        return if (gridRow < 6) 18 + gridRow * 18 else 139 + (gridRow - 6) * 18
    }
}
