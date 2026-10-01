package org.helix.cinematic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Maps linear shot progress (0..1) to eased progress (0..1).
 */
fun interface Easing {
    /**
     * @param t linear progress, 0..1.
     * @return eased progress.
     */
    fun ease(t: Double): Double

    /** Common easings. */
    companion object {
        /** Constant speed. */
        val LINEAR = Easing { it }

        /** Smoothstep: gentle start and stop – the default drone feel. */
        val SMOOTH = Easing { it * it * (3 - 2 * it) }

        /** Slow start. */
        val IN = Easing { it * it * it }

        /** Slow stop. */
        val OUT = Easing { 1 - (1 - it).pow(3) }

        /** Cubic ease-in-out (stronger than [SMOOTH]). */
        val IN_OUT = Easing { if (it < 0.5) 4 * it * it * it else 1 - (-2 * it + 2).pow(3) / 2 }

        /** Sine ease-in-out (very soft). */
        val SINE = Easing { -(cos(PI * it) - 1) / 2 }
    }
}
