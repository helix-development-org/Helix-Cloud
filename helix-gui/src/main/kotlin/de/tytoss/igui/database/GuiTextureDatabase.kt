package de.tytoss.igui.database

import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Persistence backend for texture definitions, configured via
 * [de.tytoss.igui.IGuiConfiguration.database] or
 * [de.tytoss.igui.IGuiConfiguration.postgres].
 *
 * [de.tytoss.igui.IGui] treats this as the source of truth: it loads all
 * textures from it on startup and consults it again on cache misses.
 * Implement this interface directly to back textures with, for example, a
 * plain file addons ship themselves, as a lighter-weight alternative to
 * [PostgreSQLGuiTextureDatabase]; only [changes] and [poolMetrics] need live
 * updates, and both default to "not supported" (no notifications, empty
 * pool).
 */
interface GuiTextureDatabase {

    /**
     * Loads every stored texture definition, used once on [de.tytoss.igui.IGui.install]
     * and again by [de.tytoss.igui.IGui.refreshAllTextures].
     *
     * @return all texture definitions currently stored.
     */
    suspend fun textures(): List<GuiTextureDefinition>

    /**
     * Loads a single texture definition, used on a cache miss.
     *
     * @param id the texture id to look up.
     * @return the definition, or `null` if no texture with that id exists.
     */
    suspend fun texture(id: String): GuiTextureDefinition?

    /**
     * Inserts a texture definition, or updates it if one with the same id
     * already exists.
     *
     * @param texture the definition to store.
     */
    suspend fun put(texture: GuiTextureDefinition)

    /**
     * Batch counterpart of [put]. Implementations backed by a real batch or
     * transaction API should override this; the default simply calls [put]
     * once per texture.
     *
     * @param textures the definitions to store.
     */
    suspend fun put(textures: Iterable<GuiTextureDefinition>) {
        for (texture in textures) put(texture)
    }

    /**
     * Deletes a texture definition by id.
     *
     * @param id the texture id to delete.
     * @return `true` if a texture with that id existed and was deleted.
     */
    suspend fun remove(id: String): Boolean

    /**
     * A live stream of texture changes made by other nodes/processes sharing
     * this database, so [de.tytoss.igui.IGui] can update its cache without a
     * restart. Implementations that cannot observe external changes may
     * leave this at the default, which never emits.
     *
     * @return a flow of upserts and deletes; empty if not supported.
     */
    fun changes(): Flow<GuiTextureChange> = emptyFlow()

    /**
     * Connection pool statistics for [de.tytoss.igui.IGuiMetrics], if this
     * backend is pool-based. Implementations without a pool may leave this
     * at the default, all-zero value.
     *
     * @return the current pool statistics.
     */
    fun poolMetrics(): GuiTexturePoolMetrics = GuiTexturePoolMetrics(0, 0, 0)

    /** Releases any resources (connections, listeners) held by this database. */
    suspend fun close()
}

/** The kind of change reported by [GuiTextureDatabase.changes]. */
enum class GuiTextureChangeType {
    /** A texture was inserted or updated. */
    UPSERT,

    /** A texture was deleted. */
    DELETE,
}

/**
 * A single texture change notification emitted by [GuiTextureDatabase.changes].
 *
 * @property type whether the texture was upserted or deleted.
 * @property id the id of the affected texture.
 */
data class GuiTextureChange(
    val type: GuiTextureChangeType,
    val id: String,
)

/**
 * Connection pool statistics reported by [GuiTextureDatabase.poolMetrics].
 *
 * @property activeConnections connections currently in use.
 * @property idleConnections connections open but not in use.
 * @property waitingThreads threads currently blocked waiting for a connection.
 */
data class GuiTexturePoolMetrics(
    val activeConnections: Int,
    val idleConnections: Int,
    val waitingThreads: Int,
)

/**
 * Tuning knobs for the HikariCP pool behind [PostgreSQLGuiTextureDatabase],
 * configured via [de.tytoss.igui.IGuiConfiguration.postgres].
 *
 * @property maximumPoolSize maximum (and minimum idle) number of pooled connections.
 * @property connectionTimeoutMillis time to wait for a connection before failing.
 * @property validationTimeoutMillis time allowed to validate a connection before use.
 * @property keepaliveTimeMillis interval at which idle connections are kept alive.
 * @property maxLifetimeMillis maximum lifetime of a pooled connection before it is retired.
 */
data class PostgreSQLPoolConfiguration(
    var maximumPoolSize: Int = 4,
    var connectionTimeoutMillis: Long = 5_000,
    var validationTimeoutMillis: Long = 3_000,
    var keepaliveTimeMillis: Long = 120_000,
    var maxLifetimeMillis: Long = 1_500_000,
)
