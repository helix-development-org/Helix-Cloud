package de.tytoss.igui.internal

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import java.lang.Long.numberOfLeadingZeros

/**
 * Renders invisible pixel-width "spacing" glyphs for [de.tytoss.igui.display.DisplayBuilder]'s
 * cursor movement. Relies on a companion resource pack that assigns 10
 * private/control-range codepoints per direction to glyphs of width `2^0`
 * through `2^9` pixels; an arbitrary offset is greedily decomposed into the
 * fewest such glyphs (like a binary expansion, capped at the largest
 * available power of two) and appended in sequence.
 *
 * @constructor Creates a renderer using [font] as the font namespace shared
 *  by both the positive- and negative-width glyph sets.
 */
internal class SpacingRenderer(font: Key) {
    private val positives = arrayOf(
        glyph(0x0001, font),
        glyph(0x0002, font),
        glyph(0x0003, font),
        glyph(0x0004, font),
        glyph(0x0005, font),
        glyph(0x0006, font),
        glyph(0x0007, font),
        glyph(0x0008, font),
        glyph(0x0009, font),
        glyph(0x0010, font),
    )
    private val negatives = arrayOf(
        glyph(0x1001, font),
        glyph(0x1002, font),
        glyph(0x1003, font),
        glyph(0x1004, font),
        glyph(0x1005, font),
        glyph(0x1006, font),
        glyph(0x1007, font),
        glyph(0x1008, font),
        glyph(0x1009, font),
        glyph(0x1010, font),
    )

    /**
     * Appends the fewest spacing glyphs needed to move the cursor by
     * [amount] pixels (positive or negative) onto [target].
     *
     * @param amount signed pixel offset; `0` appends nothing.
     * @param target the component builder to append glyphs to.
     */
    fun append(amount: Int, target: TextComponent.Builder) {
        if (amount == 0) return
        var remaining = kotlin.math.abs(amount.toLong())
        val glyphs = if (amount > 0) positives else negatives
        while (remaining > 0) {
            val power = minOf(9, 63 - numberOfLeadingZeros(remaining))
            target.append(glyphs[power])
            remaining -= 1L shl power
        }
    }

    private fun glyph(codePoint: Int, font: Key): Component = Component
        .text(String(Character.toChars(codePoint)), NamedTextColor.WHITE)
        .font(font)
}
