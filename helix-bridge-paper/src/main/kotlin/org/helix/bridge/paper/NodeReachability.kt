package org.helix.bridge.paper

/**
 * Tracks whether the node is currently reachable and paces retries with
 * exponential backoff while it is not, so a downed node produces one
 * rate-limited log line instead of per-cycle warning spam.
 *
 * @property clock epoch millis source, injectable for tests.
 */
class NodeReachability(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var failures = 0

    @Volatile
    private var nextAttemptAtMs = 0L

    @Volatile
    private var downSinceMs = 0L

    /** Whether enough time passed since the last failure to try again. */
    fun shouldAttempt(): Boolean = clock() >= nextAttemptAtMs

    /** Whether the node is currently considered unreachable. */
    fun isDown(): Boolean = downSinceMs != 0L

    /** Clears the down state after a successful call. */
    fun recordSuccess() {
        failures = 0
        nextAttemptAtMs = 0L
        downSinceMs = 0L
    }

    /**
     * Records a failed attempt and schedules the next one with exponential
     * backoff (capped at [MAX_BACKOFF_MS]).
     *
     * @return the epoch millis the node has been unreachable since — the
     *   growing backoff between attempts is what rate-limits how often a
     *   caller ends up logging this.
     */
    fun recordFailure(): Long {
        val now = clock()
        if (downSinceMs == 0L) {
            downSinceMs = now
        }
        val backoff = (BASE_BACKOFF_MS shl failures.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        failures++
        nextAttemptAtMs = now + backoff
        return downSinceMs
    }

    private companion object {
        /** Initial retry delay, matching the normal sync period. */
        const val BASE_BACKOFF_MS = 5_000L

        /** Upper bound on the retry delay. */
        const val MAX_BACKOFF_MS = 60_000L

        /** Bit-shift cap so the backoff computation cannot overflow before the [coerceAtMost]. */
        const val MAX_SHIFT = 4
    }
}
