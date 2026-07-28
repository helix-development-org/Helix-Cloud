package de.tytoss.igui

import de.tytoss.igui.database.GuiTextureChange
import de.tytoss.igui.database.GuiTextureChangeType
import de.tytoss.igui.database.GuiTextureDatabase
import de.tytoss.igui.database.GuiTexturePoolMetrics
import de.tytoss.igui.database.PostgreSQLGuiTextureDatabase
import de.tytoss.igui.database.PostgreSQLPoolConfiguration
import de.tytoss.igui.display.GuiFontConfiguration
import de.tytoss.igui.gui.GuiDefinition
import de.tytoss.igui.gui.GuiDefinitionBuilder
import de.tytoss.igui.gui.GuiSoundConfiguration
import de.tytoss.igui.internal.GuiRuntime
import de.tytoss.igui.internal.MetricsCollector
import de.tytoss.igui.internal.PaperDispatcher
import de.tytoss.igui.internal.TextureRegistry
import de.tytoss.igui.texture.GuiTexture
import de.tytoss.igui.texture.GuiTextureDefinition
import de.tytoss.igui.texture.IGuiObject
import de.tytoss.igui.texture.UnknownGuiTextureException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point and runtime handle for the custom-textured, resource-pack-font
 * GUI system. An instance owns the texture cache, the click/session runtime
 * and (optionally) a live [GuiTextureDatabase] connection; obtain one via
 * [IGui.install] on plugin enable and [shutdown] it on plugin disable.
 *
 * All textures configured in [IGui.install] (or already present in the
 * database) are loaded eagerly so [cachedTexture] can resolve them
 * synchronously; textures added to the database afterwards are loaded lazily
 * on first [texture] call and cached from then on.
 */
class IGui private constructor(
    private val fonts: GuiFontConfiguration,
    private val database: GuiTextureDatabase,
    private val textures: TextureRegistry,
    private val metricsCollector: MetricsCollector,
    private val runtime: GuiRuntime,
    private val scope: CoroutineScope,
    private val scopeJob: Job,
    private val paperDispatcher: PaperDispatcher,
) {
    private val closed = AtomicBoolean()
    private var listenerJob: Job? = null

    /** A snapshot of this instance's cache, runtime and database counters. */
    val metrics: IGuiMetrics
        get() = metricsCollector.snapshot(
            textures.size,
            runtime.definitionCount,
            runtime.viewerCount,
            runCatching(database::poolMetrics).getOrDefault(GuiTexturePoolMetrics(0, 0, 0)),
        )

    /**
     * Builds and registers a new GUI definition.
     *
     * @param id unique id for the GUI; must not already be registered.
     * @param block DSL block declaring the GUI's rows, pages and handlers.
     * @return the compiled, ready-to-open definition.
     */
    suspend fun gui(id: String, block: GuiDefinitionBuilder.() -> Unit): GuiDefinition =
        withContext(paperDispatcher) {
            checkOpen()
            GuiDefinitionBuilder(fonts).apply(block).build(runtime, id).also(runtime::register)
        }

    /**
     * Resolves a texture by id, loading it from the [GuiTextureDatabase] on
     * first use and caching the result.
     *
     * @param id the texture id, as configured or stored in the database.
     * @return the loaded texture.
     * @throws UnknownGuiTextureException if no texture with that id exists.
     */
    suspend fun texture(id: String): GuiTexture {
        checkOpen()
        return textures.get(id, database, scope)
    }

    /**
     * Resolves an already-cached texture without suspending or querying the
     * database. Use this in hot render paths ([de.tytoss.igui.display.DisplayBuilder]
     * blocks run synchronously); call [texture] beforehand to warm the cache.
     *
     * @param id the texture id.
     * @return the cached texture.
     * @throws IllegalArgumentException if the texture is not currently cached.
     */
    fun cachedTexture(id: String): GuiTexture {
        checkOpen()
        return textures.cached(id)
    }

    /**
     * Resolves a texture by id, falling back to [fallbackId] when [id] is not
     * a known texture.
     *
     * @param id the preferred texture id.
     * @param fallbackId the texture id to use when [id] is unknown; must differ from [id].
     * @return the loaded texture, or the fallback texture.
     * @throws UnknownGuiTextureException if neither id resolves.
     */
    suspend fun texture(id: String, fallbackId: String): GuiTexture {
        require(id != fallbackId) { "Texture and fallback ids must differ" }
        return try {
            texture(id)
        } catch (exception: UnknownGuiTextureException) {
            texture(fallbackId)
        }
    }

    /**
     * Cached counterpart of [texture] with a fallback: resolves [id] from the
     * cache, falling back to [fallbackId] if resolving [id] fails for any
     * reason (including it not being cached yet).
     *
     * @param id the preferred texture id.
     * @param fallbackId the texture id to use when [id] cannot be resolved.
     * @return the cached texture, or the fallback texture.
     */
    fun cachedTexture(id: String, fallbackId: String): GuiTexture =
        runCatching { cachedTexture(id) }.getOrElse { cachedTexture(fallbackId) }

    /**
     * Forces a fresh database load of a single texture, replacing any cached
     * value (and any cached "unknown" result) for that id.
     *
     * @param id the texture id to reload.
     * @return the freshly loaded texture.
     * @throws UnknownGuiTextureException if the texture no longer exists.
     */
    suspend fun reloadTexture(id: String): GuiTexture {
        checkOpen()
        return textures.reload(id, database, scope)
    }

    /**
     * Persists a texture definition to the database and updates the cache
     * (and any [de.tytoss.igui.texture.IGuiObject] bound to it) immediately,
     * without waiting for a [GuiTextureDatabase.changes] notification.
     *
     * @param definition the texture to insert or update.
     * @return the compiled texture now in the cache.
     */
    suspend fun saveTexture(definition: GuiTextureDefinition): GuiTexture {
        checkOpen()
        return databaseQuery {
            database.put(definition)
            textures.put(definition)
        }
    }

    /**
     * Deletes a texture from the database and, if it existed, evicts it from
     * the cache immediately.
     *
     * @param id the texture id to delete.
     * @return `true` if a texture with that id was deleted.
     */
    suspend fun deleteTexture(id: String): Boolean {
        checkOpen()
        return databaseQuery {
            database.remove(id).also { removed -> if (removed) textures.remove(id) }
        }
    }

    /**
     * Reloads every texture from the database and replaces the entire cache
     * in one step. Use sparingly; prefer letting [GuiTextureDatabase.changes]
     * (or a targeted [reloadTexture]/[saveTexture] call) keep the cache warm.
     *
     * @return the number of textures now cached.
     */
    suspend fun refreshAllTextures(): Int {
        checkOpen()
        return databaseQuery {
            database.textures().also(textures::replaceAll).size
        }.also { metricsCollector.refreshed() }
    }

    /**
     * Closes every open GUI, stops the database change listener and releases
     * the texture database connection. Idempotent; safe to call multiple
     * times or concurrently, only the first call has effect.
     */
    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        withContext(paperDispatcher) { runtime.shutdown() }
        listenerJob?.cancelAndJoin()
        scopeJob.cancelAndJoin()
        textures.close()
        withContext(NonCancellable) { database.close() }
    }

    private fun startDatabaseListener() {
        listenerJob = scope.launch {
            database.changes().collect(::handleDatabaseChange)
        }
    }

    private suspend fun handleDatabaseChange(change: GuiTextureChange) {
        if (closed.get()) return
        metricsCollector.notification()
        if (change.type == GuiTextureChangeType.DELETE) {
            textures.remove(change.id)
        } else {
            val definition = databaseQuery { database.texture(change.id) }
            if (definition == null) textures.remove(change.id) else textures.put(definition)
        }
        metricsCollector.refreshed()
    }

    private suspend fun <T> databaseQuery(action: suspend () -> T): T {
        val started = System.nanoTime()
        try {
            return action().also { metricsCollector.databaseQuery(started, failed = false) }
        } catch (exception: Exception) {
            metricsCollector.databaseQuery(started, failed = true)
            throw exception
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "IGui is already closed" }
    }

    companion object {
        /**
         * Builds and starts an [IGui] instance: connects the texture database
         * (or fails fast if none is configured), loads and caches every
         * texture, wires up the click/inventory runtime and starts listening
         * for live database changes.
         *
         * Call once per plugin, typically from `onEnable`, and [shutdown]
         * the result from `onDisable`. On any failure during startup, all
         * partially-created resources (registry, database connection) are
         * released before the exception propagates.
         *
         * @param plugin the owning Paper plugin, used for scheduling and logging.
         * @param block configures fonts, sounds, the texture database and
         *  statically-known textures.
         * @return a ready-to-use IGui instance.
         */
        suspend fun install(
            plugin: JavaPlugin,
            block: IGuiConfiguration.() -> Unit,
        ): IGui {
            val configuration = IGuiConfiguration().apply(block)
            val paperDispatcher = PaperDispatcher(plugin)
            val metrics = MetricsCollector()
            val registry = TextureRegistry()
            val database = withContext(Dispatchers.IO) {
                configuration.database ?: configuration.databaseProvider?.invoke()
                    ?: error("Configure PostgreSQL in IGui.install with postgres(...)")
            }
            try {
                if (configuration.definitions.isNotEmpty()) {
                    tracked(metrics) { database.put(configuration.definitions) }
                }
                val definitions = tracked(metrics, database::textures)
                registry.install(definitions, configuration.objects, metrics)
                val scopeJob = SupervisorJob()
                val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                    plugin.logger.severe("IGui coroutine failed: ${exception.message}")
                    exception.printStackTrace()
                }
                val scope = CoroutineScope(scopeJob + paperDispatcher + exceptionHandler)
                val runtime = withContext(paperDispatcher) {
                    GuiRuntime(plugin, metrics, configuration.sounds, scope, paperDispatcher)
                }
                plugin.logger.info("IGui cached ${registry.size} custom-font textures")
                return IGui(
                    configuration.fonts,
                    database,
                    registry,
                    metrics,
                    runtime,
                    scope,
                    scopeJob,
                    paperDispatcher,
                ).also(IGui::startDatabaseListener)
            } catch (exception: Exception) {
                registry.close()
                withContext(NonCancellable) {
                    runCatching { database.close() }.exceptionOrNull()?.let(exception::addSuppressed)
                }
                throw exception
            }
        }

        private suspend fun <T> tracked(metrics: MetricsCollector, action: suspend () -> T): T {
            val started = System.nanoTime()
            try {
                return action().also { metrics.databaseQuery(started, failed = false) }
            } catch (exception: Exception) {
                metrics.databaseQuery(started, failed = true)
                throw exception
            }
        }
    }
}


/**
 * DSL receiver passed to [IGui.install] to declare fonts, sounds, the
 * texture database backend and any statically-known textures.
 */
class IGuiConfiguration internal constructor() {
    internal val objects = ArrayList<IGuiObject>()
    internal val definitions = ArrayList<GuiTextureDefinition>()
    internal var database: GuiTextureDatabase? = null
    internal var databaseProvider: (() -> GuiTextureDatabase)? = null

    var fonts: GuiFontConfiguration = GuiFontConfiguration()
    var sounds: GuiSoundConfiguration = GuiSoundConfiguration()

    /**
     * Uses a pre-built [GuiTextureDatabase], for example a file-backed
     * implementation an addon provides itself. Mutually exclusive with
     * [postgres]; only one database may be configured.
     *
     * @param database the texture database to use.
     */
    fun database(database: GuiTextureDatabase) {
        check(this.database == null && databaseProvider == null) { "A texture database is already configured" }
        this.database = database
    }

    /**
     * Configures a [PostgreSQLGuiTextureDatabase] as the texture backend,
     * enabling live texture updates (via `LISTEN`/`NOTIFY`) without a
     * server restart. Mutually exclusive with [database].
     *
     * @param jdbcUrl a `jdbc:postgresql:` connection URL.
     * @param username database username.
     * @param password database password.
     * @param schema PostgreSQL schema to create/use the texture table in.
     * @param pool configures the underlying HikariCP connection pool.
     */
    fun postgres(
        jdbcUrl: String,
        username: String,
        password: String,
        schema: String = "public",
        pool: PostgreSQLPoolConfiguration.() -> Unit = {},
    ) {
        check(database == null && databaseProvider == null) { "A texture database is already configured" }
        val poolConfiguration = PostgreSQLPoolConfiguration().apply(pool)
        databaseProvider = {
            PostgreSQLGuiTextureDatabase(jdbcUrl, username, password, schema, poolConfiguration)
        }
    }

    /**
     * Binds the freshly loaded textures of the given ids to these
     * [IGuiObject]s, so their [IGuiObject.texture] property is available
     * immediately after [IGui.install] returns.
     *
     * @param textures the objects to bind; each must reference a texture id
     *  present in the database (or added via [texture]).
     */
    fun textures(vararg textures: IGuiObject) {
        objects += textures
    }

    /**
     * Single-object overload of [textures].
     *
     * @param texture the object to bind.
     */
    fun texture(texture: IGuiObject) {
        objects += texture
    }

    /**
     * Seeds the database with a texture definition on startup (inserted if
     * not already present, see [GuiTextureDatabase.put]).
     *
     * @param definition the texture to seed.
     */
    fun texture(definition: GuiTextureDefinition) {
        definitions += definition
    }

    /**
     * Convenience overload of [texture] that builds a [GuiTextureDefinition]
     * from its individual fields, resolving [font] against [fonts] namespace
     * defaults.
     *
     * @param id unique texture id.
     * @param character the single-codepoint character rendered by the font.
     * @param font font name; namespaced automatically unless it already
     *  contains a `:`.
     * @param widthPixels rendered width, in pixels.
     * @param heightPixels rendered height, in pixels.
     * @param advancePixels cursor advance after rendering, in pixels.
     * @param clientAnimated whether the underlying resource-pack texture is
     *  an animated (`.mcmeta`) texture.
     * @return the created definition, already added to the seed list.
     */
    fun texture(
        id: String,
        character: String,
        font: String,
        widthPixels: Int,
        heightPixels: Int = 18,
        advancePixels: Int = widthPixels + 1,
        clientAnimated: Boolean = false,
    ): GuiTextureDefinition = GuiTextureDefinition(
        id = id,
        character = character,
        font = fonts.font(font),
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        advancePixels = advancePixels,
        clientAnimated = clientAnimated,
    ).also(definitions::add)
}
