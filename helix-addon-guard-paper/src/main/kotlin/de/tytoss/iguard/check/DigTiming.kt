package de.tytoss.iguard.check

import de.tytoss.iguard.config.TuningConfig
import de.tytoss.iguard.model.DigSample
import kotlin.math.ceil
import kotlin.math.round

/**
 * Vanilla dig timing for `world.fastbreak.a`.
 *
 * The client adds the block's break progress once per tick – from the START tick on when the dig starts with a
 * click, from the tick after when holding the button carries over from the previous block – and sends FINISH in
 * the tick the sum reaches 1. At `p` progress per tick at least `ceil(1 / p) - 1` client ticks therefore lie between
 * START and FINISH. Blocks with `p >= 1` break instantly (vanilla sends no FINISH) and are never judged.
 */
internal object DigTiming {
    /** Length of a client tick in milliseconds. */
    const val TICK_MILLIS = 50L

    // Vanilla sums float progress; a sum landing a hair above 1 one tick early must not flag.
    private const val EPSILON = 1.0E-6

    /**
     * Fewest client ticks a vanilla client needs between START and FINISH.
     *
     * @param progressPerTick break progress per tick as the server measured it.
     * @return the ticks, or null for instant (`>= 1`) or unmeasurable (`<= 0`, not finite) blocks.
     */
    fun minimumTicks(progressPerTick: Double): Int? {
        if (!progressPerTick.isFinite() || progressPerTick <= 0.0 || progressPerTick >= 1.0) return null
        return ceil(1.0 / progressPerTick - EPSILON).toInt() - 1
    }

    /**
     * Judges one finished dig.
     *
     * With tick-end packets ([ticks] not null) the client ticks between START and FINISH are counted – immune to
     * network jitter. Older clients are timed in milliseconds with [TuningConfig.fastbreakToleranceMillis] plus a
     * quarter of the ping as allowance for a START delayed against its FINISH.
     *
     * @param dig the server's measurement.
     * @param ticks client ticks between START and FINISH, null without tick-end packets.
     * @param millis wall-clock time between START and FINISH.
     * @param ping current ping.
     * @param tuning thresholds.
     * @return the evidence when the dig was too fast, otherwise null.
     */
    fun judge(dig: DigSample, ticks: Long?, millis: Long, ping: Int, tuning: TuningConfig): Map<String, Any>? {
        val minimum = minimumTicks(dig.progressPerTick) ?: return null
        val required = minimum * tuning.fastbreakMinRatio
        val tooFast = if (ticks != null) {
            ticks < required
        } else {
            millis + tuning.fastbreakToleranceMillis + ping.coerceIn(0, MAX_PING) / 4 < required * TICK_MILLIS
        }
        if (!tooFast) return null
        return buildMap {
            put("block", dig.block)
            put("progressPerTick", round(dig.progressPerTick * 10000.0) / 10000.0)
            put("minimumTicks", minimum)
            if (ticks != null) put("ticks", ticks)
            put("millis", millis)
            put("source", if (ticks != null) "tick-end" else "millis")
        }
    }

    private const val MAX_PING = 400
}
