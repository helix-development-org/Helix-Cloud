package de.tytoss.igui.screen

import de.tytoss.igui.display.DisplayBuilder
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor

/** Horizontal anchor of [textAt]. */
enum class TextAlign {
    /** Text starts at x. */
    LEFT,

    /** Text is centred on x. */
    CENTER,

    /** Text ends at x. */
    RIGHT,
}

/**
 * Draws a glyph of a screen font with its left edge at container x [containerX].
 *
 * @param containerX left edge in container pixels.
 * @param font the glyph's font.
 * @param codePoint the glyph's character.
 * @param advance the glyph's advance (image width + 1).
 */
fun DisplayBuilder.glyphAt(containerX: Int, font: Key, codePoint: Int, advance: Int) {
    val c = String(Character.toChars(codePoint))
    characterWidth(c, advance)
    moveTo(ScreenFonts.titleX(containerX))
    text(c, 0, NamedTextColor.WHITE, font)
}

/**
 * Draws text at any position using the shared per-height text fonts.
 *
 * @param value the text (ASCII and Latin-1 letters).
 * @param containerX anchor x in container pixels.
 * @param top text top in container pixels.
 * @param color text colour.
 * @param align how the text sits relative to [containerX].
 * @param shadow colour of a 1 px drop shadow (down right; design concept: white text always with an ink shadow), or null.
 */
fun DisplayBuilder.textAt(
    value: String,
    containerX: Int,
    top: Int,
    color: TextColor = NamedTextColor.WHITE,
    align: TextAlign = TextAlign.LEFT,
    shadow: TextColor? = null,
) {
    if (value.isEmpty()) return
    val width = measure(value)
    val left = when (align) {
        TextAlign.LEFT -> containerX
        TextAlign.CENTER -> containerX - width / 2
        TextAlign.RIGHT -> containerX - width
    }
    if (shadow != null) {
        moveTo(ScreenFonts.titleX(left + 1))
        text(value, 0, shadow, Key.key(ScreenFonts.textFont(top + 1)))
    }
    moveTo(ScreenFonts.titleX(left))
    text(value, 0, color, Key.key(ScreenFonts.textFont(top)))
}

/**
 * Shortens [value] with "…" so it fits [maxWidth] pixels.
 *
 * @param value the text.
 * @param maxWidth available width.
 * @return the text, shortened if needed.
 */
fun DisplayBuilder.fit(value: String, maxWidth: Int): String {
    if (measure(value) <= maxWidth) return value
    var cut = value
    while (cut.isNotEmpty() && measure("$cut…") > maxWidth) cut = cut.dropLast(1)
    return "$cut…"
}
