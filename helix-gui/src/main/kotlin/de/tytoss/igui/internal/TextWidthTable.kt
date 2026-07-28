package de.tytoss.igui.internal

/**
 * Per-[de.tytoss.igui.display.DisplayBuilder] table of rendered glyph
 * widths, used to compute how far the cursor must advance after appending
 * text so subsequent elements stay pixel-aligned. Seeded with the vanilla
 * Minecraft default font's known-narrow ASCII glyph widths (a 6px width is
 * assumed for anything not explicitly listed), and extendable per-character
 * via [register] for custom fonts.
 */
internal class TextWidthTable {
    private val ascii = IntArray(128) { 6 }
    private val custom = HashMap<Int, Int>()

    init {
        ascii[' '.code] = 4
        set(2, '!', '\'', ',', '.', ':', ';', 'i', '|')
        set(3, '"', '`', 'l')
        set(4, '(', ')', '*', '[', ']', '{', '}', 'I', 't')
        set(5, '<', '>', 'f', 'k')
        ascii['@'.code] = 7
        ascii['~'.code] = 7
        custom['√'.code] = 7
    }

    /**
     * Overrides the rendered width of a single character, for glyphs whose
     * width differs from the built-in ASCII table.
     *
     * @param character exactly one Unicode code point.
     * @param width the character's rendered width, in pixels.
     */
    fun register(character: String, width: Int) {
        require(character.codePointCount(0, character.length) == 1) {
            "A custom width must target exactly one Unicode code point"
        }
        require(width >= 0) { "Character width must not be negative" }
        custom[character.codePointAt(0)] = width
    }

    /**
     * Sums the rendered width of every code point in [text].
     *
     * @param text the text to measure.
     * @return the total width, in pixels.
     */
    fun measure(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += custom[codePoint] ?: if (codePoint < ascii.size) ascii[codePoint] else 6
            index += Character.charCount(codePoint)
        }
        return width
    }

    private fun set(width: Int, vararg characters: Char) {
        characters.forEach { ascii[it.code] = width }
    }
}
