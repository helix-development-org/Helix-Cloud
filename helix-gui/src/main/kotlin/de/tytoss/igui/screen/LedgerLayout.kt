package de.tytoss.igui.screen

/**
 * Layout of the shared **ledger** ("Pergament-Buch", design concept §6/§7) in container pixels (pure; shared with
 * the Helix-GUIs pack generator): a 384×256 desk board (wood frame, iron corners) with the list page over the slot
 * grid (title ribbon, eight list rows on grid rows 1..8, the footer on grid row 9), an info note with a wax seal on
 * the right and a desk with books and the one life sign (candle or quill) on the left.
 *
 * Footer (grid row 9): back arrow (column 0) · left button (columns 1–2, 36 px) · page arrow, page number, page
 * arrow (columns 3–5) · right button (columns 6–8, 54 px). Closing is ESC only.
 */
object LedgerLayout {
    /** Font of the ledger glyphs (`helix_guis:ledger`). */
    const val FONT = "helix_guis:ledger"

    /** Board image size and position (same frame as every full-window screen). */
    const val BG_W = 384
    const val BG_H = 256
    const val BG_X = -104
    const val BG_Y = -17

    /** Glyphs: board tiles, arrows. */
    const val BG_TILE = 0xE000
    const val ARROW_BACK = 0xE010
    const val ARROW_LEFT = 0xE011
    const val ARROW_RIGHT = 0xE012
    const val ARROW_LEFT_OFF = 0xE013
    const val ARROW_RIGHT_OFF = 0xE014
    const val ARROW_SIZE = 18

    /** Buttons: glyph `BUTTON_SMALL + style` (36 px) and `BUTTON_WIDE + style` (54 px), 18 px high. */
    const val BUTTON_SMALL = 0xE020
    const val BUTTON_WIDE = 0xE028
    const val BUTTON_SMALL_W = 36
    const val BUTTON_WIDE_W = 54
    const val BUTTON_H = 18

    /** Art file infixes of the button styles, in glyph order (= `ButtonStyle` order). */
    val BUTTON_FILES = listOf("primary", "active", "secondary", "danger", "disabled")

    /** Gold strip behind a selected row, one glyph per row (164×19). */
    const val ROW_SELECTED = 0xE030
    const val ROW_SELECTED_X = 6
    const val ROW_SELECTED_W = 164

    /** Speaker heads (32×32) of empty states, glyph `EMPTY_HEAD + villager index` (see [VillagerHeads]). */
    const val EMPTY_HEAD = 0xE040
    const val EMPTY_HEAD_SIZE = 32
    const val EMPTY_HEAD_TOP = 50
    const val EMPTY_TEXT_TOP = 88
    const val EMPTY_TEXT_LINE = 10
    const val EMPTY_TEXT_WIDTH = 150

    /**
     * Badge pills (9 px high): glyph `PILL + row * 32 + color * 8 + part`; part 0 = left cap, 1 = right cap,
     * 2..7 = middle pieces of 1, 2, 4, 8, 16 and 32 px.
     */
    const val PILL = 0xE100
    const val PILL_H = 9
    const val PILL_CAP = 3
    val PILL_PIECES = intArrayOf(1, 2, 4, 8, 16, 32)

    /** Art file infixes of the pill colours, in glyph order (= `PillColor` order). */
    val PILL_FILES = listOf("red", "green", "grey", "gold")

    /** Rows per page and their grid rows. */
    const val ROWS = 8
    const val FIRST_ROW = 1
    const val FOOTER_ROW = 9

    /** Footer geometry: glyph top, back arrow, buttons, paging. */
    const val FOOTER_TOP = 196
    const val BACK_COLUMN = 0
    const val PREV_COLUMN = 3
    const val PAGE_COLUMN = 4
    const val NEXT_COLUMN = 5
    val BUTTON_COLUMNS = arrayOf(1..2, 6..8)

    /**
     * @param column grid column 0..8.
     * @return the left edge of a cell in that column.
     */
    fun cellX(column: Int): Int = 7 + column * 18

    /** Title on the ribbon, subtitle under it. */
    const val TITLE_TOP = 5
    const val SUBTITLE_TOP = 22
    const val CENTER_X = 88

    /** Row texts: label from the left (after an icon in column 0), value right-aligned. */
    const val LABEL_X = 28
    const val LABEL_X_NO_ICON = 12
    const val VALUE_RIGHT = 165
    const val ROW_TEXT_OFFSET = 5

    /** Right edge of a row's value when the row has a badge in column 8. */
    const val VALUE_RIGHT_BADGE = 147

    /** Info note (right): centre x, first line top, line height, line count, width. */
    const val NOTE_X = 226
    const val NOTE_TOP = 24
    const val NOTE_LINE = 11
    const val NOTE_LINES = 16
    const val NOTE_WIDTH = 74

    /** Former left note; unused since v2 (kept for source compatibility). */
    const val LEFT_X = -50

    /** Former name of [NOTE_X]. */
    const val RIGHT_X = NOTE_X

    /**
     * @param index row 0..7.
     * @return the row's top edge (item top; the slot grid has gaps between chest and inventory rows).
     */
    fun rowTop(index: Int): Int {
        val gridRow = FIRST_ROW + index
        return if (gridRow < 6) 18 + gridRow * 18 else 139 + (gridRow - 6) * 18
    }

    /** Namespace of the ledger sprites. */
    const val SPRITE_NAMESPACE = "helix_guis"

    /** Life signs on the desk (header slot 0): a flickering candle or a writing quill on the paper of the book stack. */
    val CANDLE = ScreenSprite("anim/candle_desk", "anim/candle", 32, column = 0, row = 0, centerX = -50, centerY = 85)
    val QUILL = ScreenSprite("anim/quill_desk", "anim/quill", 32, column = 0, row = 0, centerX = -57, centerY = 92)

    /** Badges drawn in a row's last cell (column 8, no shift). */
    val SEAL = ScreenSprite("anim/seal", "anim/seal", 16, column = 8, row = 1)
    val GEM_ON = ScreenSprite("anim/gem_on", "anim/gem_on", 16, column = 8, row = 1)
    val GEM_OFF = ScreenSprite("anim/gem_off", "anim/gem_off", 16, column = 8, row = 1)
    val RUNE = ScreenSprite("anim/rune", "anim/rune", 16, column = 8, row = 1)

    /** Every ledger sprite (for the pack generator). */
    val SPRITES = listOf(CANDLE, QUILL, SEAL, GEM_ON, GEM_OFF, RUNE)
}
