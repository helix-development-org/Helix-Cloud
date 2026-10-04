package de.tytoss.igui.screen

/**
 * Pack-side helpers for full-window "screens" (pure, usable from resource-pack generators): a screen is drawn
 * entirely as glyphs in the GUI title, positioned in **container pixels** (x from the container's left edge,
 * y from its top edge; the player-inventory GUI is 176×222). Glyph tops become font ascents, big images are cut
 * into tiles (the client's glyph page is 256×256), and text can sit at any height through the shared
 * per-height text fonts of the Helix-GUIs pack ([textFont]).
 */
object ScreenFonts {
    /** Namespace of the shared screen fonts (the Helix-GUIs pack). */
    const val NAMESPACE = "helix_guis"

    /** Heights (container pixels, text top) the shared text fonts exist for (vanilla allows ascent ≤ height, so text starts at y 5). */
    val TEXT_TOPS: IntRange = 5..320

    /** Largest glyph edge the client renders. */
    const val MAX_GLYPH = 256

    /**
     * @param top top edge of a glyph in container pixels.
     * @return the font ascent that puts it there (the title baseline sits at y 13).
     */
    fun ascent(top: Int): Int = TITLE_BASELINE - top

    /**
     * @param containerX x in container pixels.
     * @return the title cursor position for it (the title starts at container x 8).
     */
    fun titleX(containerX: Int): Int = containerX - TITLE_LEFT

    /**
     * @param top text top in container pixels (within [TEXT_TOPS]).
     * @return the shared text font key (`helix_guis:screen/t<top>`).
     */
    fun textFont(top: Int): String {
        require(top in TEXT_TOPS) { "text top $top outside $TEXT_TOPS" }
        return "$NAMESPACE:screen/t$top"
    }

    /**
     * @param top text top in container pixels.
     * @return the font JSON of the shared text font for that height (vanilla glyphs, shifted).
     */
    fun textFontJson(top: Int): String {
        val chars = ASCII_GRID.joinToString(",") { row -> "\"" + escape(row) + "\"" }
        return """{"providers":[{"type":"space","advances":{" ":4}},""" +
            """{"type":"bitmap","file":"minecraft:font/ascii.png","height":8,"ascent":${ascent(top)},"chars":[$chars]}]}"""
    }

    /**
     * One tile of an image cut for glyph use.
     *
     * @property x left edge inside the image.
     * @property y top edge inside the image.
     * @property width tile width.
     * @property height tile height.
     */
    data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Cuts an image into tiles no larger than [MAX_GLYPH] (row by row).
     *
     * @param width image width.
     * @param height image height.
     * @return the tiles.
     */
    fun tiles(width: Int, height: Int): List<Tile> {
        val cols = (width + MAX_GLYPH - 1) / MAX_GLYPH
        val rows = (height + MAX_GLYPH - 1) / MAX_GLYPH
        val tileW = (width + cols - 1) / cols
        val tileH = (height + rows - 1) / rows
        return (0 until rows).flatMap { r ->
            (0 until cols).map { c ->
                Tile(c * tileW, r * tileH, minOf(tileW, width - c * tileW), minOf(tileH, height - r * tileH))
            }
        }
    }

    /**
     * Bitmap provider JSON for one glyph.
     *
     * @param file texture (`namespace:path.png`).
     * @param top glyph top in container pixels.
     * @param height glyph height (= image height for 1:1 pixels).
     * @param codePoint the glyph's character.
     * @return the provider JSON object.
     */
    fun bitmap(file: String, top: Int, height: Int, codePoint: Int): String {
        require(ascent(top) <= height) { "glyph top $top too high for height $height (ascent must not exceed height)" }
        return """{"type":"bitmap","file":"$file","height":$height,"ascent":${ascent(top)},"chars":["${escape(String(Character.toChars(codePoint)))}"]}"""
    }

    /**
     * Escapes a string for JSON (non-ASCII as `\uXXXX`).
     *
     * @param text the text.
     * @return the JSON-safe text (without quotes).
     */
    fun escape(text: String): String = buildString {
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.code < 0x20 || c.code > 0x7E -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    private const val TITLE_BASELINE = 13
    private const val TITLE_LEFT = 8

    /** Character grid of `minecraft:font/ascii.png` (16×16), as vanilla's default font maps it. */
    private val ASCII_GRID = listOf(
        "ÀÁÂÈÊËÍÓÔÕÚßãõğİ",
        "ıŒœŞşŴŵžȇ\u0000\u0000\u0000\u0000\u0000\u0000\u0000",
        " !\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO", "PQRSTUVWXYZ[\\]^_", "`abcdefghijklmno", "pqrstuvwxyz{|}~\u0000",
        "ÇüéâäàåçêëèïîìÄÅ",
        "ÉæÆôöòûùÿÖÜø£Ø×ƒ",
        "áíóúñÑªº¿®¬½¼¡«»",
        "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐",
        "└┴┬├─┼╞╟╚╔╩╦╠═╬╧",
        "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀",
        "αβΓπΣσμτΦΘΩδ∞∅∈∩",
        "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u0000",
    )
}
