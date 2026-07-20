package org.helix.node.proxy

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wakes long-polling proxy bridges the instant something changes, so
 * commands, routing updates and new player-commands reach proxies without
 * waiting for a poll interval.
 *
 * Two monotonically increasing versions let a bridge detect whether it
 * must re-fetch routing or the player-command catalog; a shared wake-up
 * flow unblocks waiters immediately on any change.
 */
class ProxyEventHub {
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 256)

    /** Bumped whenever the routing snapshot may have changed. */
    val routingVersion = AtomicInteger(0)

    /** Bumped whenever the set of player-commands changed. */
    val commandCatalogVersion = AtomicInteger(0)

    /** Signals waiters without changing a version (for queued commands). */
    fun signal() {
        wake.tryEmit(Unit)
    }

    /** Marks routing as changed and wakes waiters. */
    fun bumpRouting() {
        routingVersion.incrementAndGet()
        signal()
    }

    /** Marks the player-command catalog as changed and wakes waiters. */
    fun bumpCommandCatalog() {
        commandCatalogVersion.incrementAndGet()
        signal()
    }

    /**
     * Suspends until the next signal or the timeout elapses.
     *
     * @param timeoutMs maximum wait in milliseconds.
     */
    suspend fun await(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { wake.first() }
    }
}
