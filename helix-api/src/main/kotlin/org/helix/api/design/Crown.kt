package org.helix.api.design

/**
 * The network currency icon „Krone“ (design concept §8): a gold spiked crown with three gems, a glyph of the shared
 * GUI pack (`helix_guis:icons`). Shown at half size on the text line, so it fits chat, action bar and price pills.
 */
object Crown {
    /** Font of the icon glyphs. */
    const val FONT = "helix_guis:icons"

    /** Crown glyph codepoint (U+E000 in [FONT]). */
    const val GLYPH = ""

    /** MiniMessage of the crown icon (white = untinted), value of the global `{crown}` placeholder. */
    const val MINI = "<white><font:$FONT>$GLYPH</font></white>"
}
