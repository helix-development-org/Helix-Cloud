package org.helix.cinematic

/**
 * How a shot begins: a hard cut or a fade through black (the screen is covered by a full-screen font glyph from
 * the HelixCinematic resource pack, shown as title – the cut happens while it is black).
 */
sealed interface Transition {
    /** Ticks from the start of the transition until the screen is fully covered (the cut happens then). */
    val coverTicks: Int

    /** Hard cut. */
    data object Cut : Transition {
        override val coverTicks: Int = 0
    }

    /**
     * Fade to black and back.
     *
     * @property inTicks fade to black.
     * @property holdTicks fully black.
     * @property outTicks fade back in.
     */
    data class Fade(val inTicks: Int = 8, val holdTicks: Int = 4, val outTicks: Int = 10) : Transition {
        override val coverTicks: Int get() = inTicks + holdTicks / 2
    }

    /** Defaults. */
    companion object {
        /** The standard fade. */
        val FADE: Transition = Fade()
    }
}
