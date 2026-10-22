package org.helix.api.design

/**
 * The network wordmark (design concept §19) as pixel glyphs of the shared GUI pack (`helix_guis:wordmark`): gold
 * cartoon lettering with ink outline and hard shadow plus a red "MC" plaque, painted in Blockbench as "KronlandMC"
 * (release name of the network). Repainting the two glyph images changes the mark; the placeholders stay the same.
 */
object Wordmark {
    /** Font of the wordmark glyphs. */
    const val FONT = "helix_guis:wordmark"

    /** Large glyph (≈120×27), for titles. */
    const val LARGE = "\uE000"

    /** Small glyph (≈64×18), for tablist and scoreboard. */
    const val SMALL = "\uE001"

    /** MiniMessage of the small wordmark (white = untinted), value of the global `{wordmark}` placeholder. */
    const val SMALL_MINI = "<white><font:$FONT>$SMALL</font></white>"

    /** MiniMessage of the large wordmark, value of the global `{wordmark_large}` placeholder. */
    const val LARGE_MINI = "<white><font:$FONT>$LARGE</font></white>"
}
