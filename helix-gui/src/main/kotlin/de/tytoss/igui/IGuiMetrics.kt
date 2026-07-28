package de.tytoss.igui

/**
 * A point-in-time snapshot of an [IGui] instance's operational counters, as
 * returned by [IGui.metrics]. Useful for exposing GUI health on a dashboard
 * or in `/helix` diagnostics without holding a reference to internal state.
 *
 * @property loadedTextures number of textures currently cached in memory.
 * @property registeredGuis number of [de.tytoss.igui.gui.GuiDefinition]s
 *  currently registered on the runtime.
 * @property openViewers number of players currently viewing an IGui inventory.
 * @property inventoriesOpened total inventories opened since startup.
 * @property clickCallbacks total slot click handlers invoked since startup.
 * @property averageOpenMicros mean time to render and open an inventory, in
 *  microseconds.
 * @property cacheHits texture lookups served from an already-loaded cache entry.
 * @property cacheMisses texture lookups that had to load from the database.
 * @property negativeCacheHits texture lookups that hit a cached "unknown texture"
 *  result without re-querying the database.
 * @property databaseQueries total texture database queries issued.
 * @property databaseFailures texture database queries that threw an exception.
 * @property averageDatabaseMicros mean texture database query duration, in
 *  microseconds.
 * @property notificationsReceived texture change notifications received from
 *  [de.tytoss.igui.database.GuiTextureDatabase.changes].
 * @property cacheRefreshes texture cache refresh operations completed (initial
 *  load, live updates and [IGui.refreshAllTextures] combined).
 * @property poolActiveConnections active connections in the database connection
 *  pool, or `0` when the database does not report pool metrics.
 * @property poolIdleConnections idle connections in the database connection pool.
 * @property poolWaitingThreads threads currently waiting for a pool connection.
 */
data class IGuiMetrics(
    val loadedTextures: Int,
    val registeredGuis: Int,
    val openViewers: Int,
    val inventoriesOpened: Long,
    val clickCallbacks: Long,
    val averageOpenMicros: Double,
    val cacheHits: Long,
    val cacheMisses: Long,
    val negativeCacheHits: Long,
    val databaseQueries: Long,
    val databaseFailures: Long,
    val averageDatabaseMicros: Double,
    val notificationsReceived: Long,
    val cacheRefreshes: Long,
    val poolActiveConnections: Int,
    val poolIdleConnections: Int,
    val poolWaitingThreads: Int,
)
