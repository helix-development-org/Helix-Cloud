package de.tytoss.iguard.snapshot

import de.tytoss.iguard.model.DigSample
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands dig measurements from the main thread to the packet threads, one active dig per player.
 *
 * The packet listener registers a dig on START_DIG ([start]); every tick the [MainThreadSampler] asks the
 * server how fast the player breaks that block ([measure]); the listener attaches the result to the
 * FINISH_DIG frame ([finish]), so the fastbreak check and trace replays see the same numbers. The fastest
 * measured speed is kept – the most lenient basis when the player's speed changes mid-dig.
 */
class DigProbe {
    private class Dig(val x: Int, val y: Int, val z: Int, val startedAt: Long) {
        @Volatile var sample: DigSample? = null
    }

    private val digs = ConcurrentHashMap<UUID, Dig>()

    /**
     * Registers a dig (replaces the player's previous one).
     *
     * @param playerId digging player.
     * @param x block x.
     * @param y block y.
     * @param z block z.
     * @param now packet time (ms).
     */
    fun start(playerId: UUID, x: Int, y: Int, z: Int, now: Long) {
        digs[playerId] = Dig(x, y, z, now)
    }

    /**
     * Drops the player's dig (CANCEL_DIG, quit).
     *
     * @param playerId the player.
     */
    fun cancel(playerId: UUID) {
        digs.remove(playerId)
    }

    /**
     * Ends the player's dig of the given block.
     *
     * @param playerId the player.
     * @param x block x.
     * @param y block y.
     * @param z block z.
     * @return the measurement, or null when the dig was not registered or not measured yet.
     */
    fun finish(playerId: UUID, x: Int, y: Int, z: Int): DigSample? {
        val dig = digs[playerId]?.takeIf { it.x == x && it.y == y && it.z == z } ?: return null
        digs.remove(playerId, dig)
        return dig.sample
    }

    /**
     * Measures every active dig (main thread only). Digs older than [maxAgeMillis] are dropped.
     *
     * @param now current time (ms).
     * @param maxAgeMillis longest dig kept.
     * @param speed `(player, x, y, z)` → block key and break progress per tick, or null when the block cannot be
     *   measured (player offline, chunk unloaded, block already broken).
     */
    fun measure(now: Long, maxAgeMillis: Long = MAX_AGE_MILLIS, speed: (UUID, Int, Int, Int) -> Pair<String, Double>?) {
        digs.forEach { (playerId, dig) ->
            if (now - dig.startedAt > maxAgeMillis) {
                digs.remove(playerId, dig)
                return@forEach
            }
            val (block, progress) = speed(playerId, dig.x, dig.y, dig.z) ?: return@forEach
            if (!progress.isFinite() || progress < 0.0) return@forEach
            val before = dig.sample
            dig.sample = DigSample(maxOf(progress, before?.progressPerTick ?: 0.0), (before?.samples ?: 0) + 1, block)
        }
    }

    /** Number of active digs. */
    fun size(): Int = digs.size

    private companion object {
        const val MAX_AGE_MILLIS = 60_000L
    }
}
