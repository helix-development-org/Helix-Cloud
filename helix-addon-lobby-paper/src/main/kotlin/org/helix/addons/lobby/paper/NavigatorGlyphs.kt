package org.helix.addons.lobby.paper

/**
 * Layout of the navigator screen in container pixels (pure; shared with the pack generator so art and clicks
 * line up): a 384×256 board (dark wood, parchment map, ribbon, two banners) centred on the container, and six coats
 * of arms on the parchment in a 3×2 grid aligned with the slot grid (each card = 3 columns × 5 rows of cells).
 */
object NavigatorGlyphs {
    /** Font of all navigator glyphs (`helix_lobby:navigator`). */
    const val FONT = "helix_lobby:navigator"

    /** Board image size and position. */
    const val BG_W = 384
    const val BG_H = 256
    const val BG_X = -104
    const val BG_Y = -17

    /** Board tiles (left/right half), glyphs U+E000.. */
    const val BG_TILE = 0xE000
    const val BG_TILE_W = 192

    /** Coat-of-arms shield (48×58), glyph per card row. */
    const val SHIELD = 0xE010
    const val SHIELD_W = 48
    const val SHIELD_H = 58

    /** Emblems (32×32) inside the shield, glyph per emblem and card row. */
    const val EMBLEM = 0xE020
    const val EMBLEM_SIZE = 32

    /** Emblem ids, in glyph order. */
    val EMBLEMS = listOf("castle", "swords", "banner", "compass", "crown")

    /** Cards: 3 columns × 2 rows. */
    const val COLUMNS = 3
    const val ROWS = 2
    const val CARDS = COLUMNS * ROWS

    /** Headline on the ribbon. */
    const val TITLE_TOP = 8
    const val CENTER_X = 88

    /**
     * @param column card column 0..2.
     * @return the card's horizontal centre.
     */
    fun cardCenter(column: Int): Int = 34 + column * 54

    /** Shield top per card row. */
    fun shieldTop(row: Int): Int = if (row == 0) 21 else 113

    /** Emblem top per card row. */
    fun emblemTop(row: Int): Int = shieldTop(row) + 10

    /** Name text top per card row. */
    fun nameTop(row: Int): Int = if (row == 0) 81 else 174

    /** Player-count text top per card row. */
    fun countTop(row: Int): Int = if (row == 0) 92 else 185

    /**
     * @param emblem index in [EMBLEMS].
     * @param row card row.
     * @return the emblem glyph for that row.
     */
    fun emblem(emblem: Int, row: Int): Int = EMBLEM + emblem * ROWS + row

    /** Card [index] (0..5) → its first slot cell (column, row) in the 9×10 grid. */
    fun cardCell(index: Int): Pair<Int, Int> = (index % COLUMNS) * 3 to (index / COLUMNS) * 5
}
