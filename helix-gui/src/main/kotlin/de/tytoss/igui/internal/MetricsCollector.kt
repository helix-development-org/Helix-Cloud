package de.tytoss.igui.internal

import de.tytoss.igui.IGuiMetrics
import de.tytoss.igui.database.GuiTexturePoolMetrics
import java.util.concurrent.atomic.LongAdder

/**
 * Lock-free counters backing [IGuiMetrics]; one instance is shared by an
 * [de.tytoss.igui.IGui] and its runtime/registry collaborators for the
 * lifetime of the instance.
 */
internal class MetricsCollector {
    private val opens = LongAdder()
    private val openNanos = LongAdder()
    private val callbacks = LongAdder()
    private val cacheHits = LongAdder()
    private val cacheMisses = LongAdder()
    private val negativeCacheHits = LongAdder()
    private val databaseQueries = LongAdder()
    private val databaseFailures = LongAdder()
    private val databaseNanos = LongAdder()
    private val notifications = LongAdder()
    private val refreshes = LongAdder()

    /** Records that an inventory was opened, taking [nanos] to render. */
    fun opened(nanos: Long) {
        opens.increment()
        openNanos.add(nanos)
    }

    /** Records that a slot click handler was invoked. */
    fun callback() {
        callbacks.increment()
    }

    /** Records a texture cache hit (already-loaded value reused). */
    fun cacheHit() = cacheHits.increment()

    /** Records a texture cache miss (had to load from the database). */
    fun cacheMiss() = cacheMisses.increment()

    /** Records a cache hit against a previously cached "unknown texture" result. */
    fun negativeCacheHit() = negativeCacheHits.increment()

    /** Records a texture change notification received from the database. */
    fun notification() = notifications.increment()

    /** Records a completed texture cache refresh. */
    fun refreshed() = refreshes.increment()

    /**
     * Records a texture database query.
     *
     * @param startedNanos [System.nanoTime] captured before the query started.
     * @param failed whether the query threw an exception.
     */
    fun databaseQuery(startedNanos: Long, failed: Boolean) {
        databaseQueries.increment()
        databaseNanos.add(System.nanoTime() - startedNanos)
        if (failed) databaseFailures.increment()
    }

    /**
     * Builds an [IGuiMetrics] snapshot from the current counters plus the
     * given point-in-time state.
     *
     * @param textures number of textures currently cached.
     * @param guis number of GUI definitions currently registered.
     * @param viewers number of players currently viewing a GUI.
     * @param pool the texture database's connection pool statistics.
     * @return the assembled metrics snapshot.
     */
    fun snapshot(textures: Int, guis: Int, viewers: Int, pool: GuiTexturePoolMetrics): IGuiMetrics {
        val openCount = opens.sum()
        val queryCount = databaseQueries.sum()
        return IGuiMetrics(
            loadedTextures = textures,
            registeredGuis = guis,
            openViewers = viewers,
            inventoriesOpened = openCount,
            clickCallbacks = callbacks.sum(),
            averageOpenMicros = if (openCount == 0L) 0.0 else openNanos.sum() / openCount / 1_000.0,
            cacheHits = cacheHits.sum(),
            cacheMisses = cacheMisses.sum(),
            negativeCacheHits = negativeCacheHits.sum(),
            databaseQueries = queryCount,
            databaseFailures = databaseFailures.sum(),
            averageDatabaseMicros = if (queryCount == 0L) 0.0 else databaseNanos.sum() / queryCount / 1_000.0,
            notificationsReceived = notifications.sum(),
            cacheRefreshes = refreshes.sum(),
            poolActiveConnections = pool.activeConnections,
            poolIdleConnections = pool.idleConnections,
            poolWaitingThreads = pool.waitingThreads,
        )
    }
}
