package de.tytoss.igui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScreenFontsTest {
    @Test
    fun `tops map to ascents relative to the title baseline`() {
        assertEquals(13, ScreenFonts.ascent(0))
        assertEquals(-87, ScreenFonts.ascent(100))
        assertEquals(-8, ScreenFonts.titleX(0))
    }

    @Test
    fun `images larger than a glyph page are cut into tiles`() {
        val tiles = ScreenFonts.tiles(384, 256)
        assertEquals(2, tiles.size)
        assertEquals(ScreenFonts.Tile(192, 0, 192, 256), tiles[1])
        assertEquals(1, ScreenFonts.tiles(200, 100).size)
    }

    @Test
    fun `a glyph may not reach above its own height`() {
        assertFailsWith<IllegalArgumentException> { ScreenFonts.bitmap("a:b.png", top = -30, height = 8, codePoint = 0xE000) }
        ScreenFonts.bitmap("a:b.png", top = -17, height = 256, codePoint = 0xE000)
    }
}
