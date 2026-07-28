package de.tytoss.igui.internal

import de.tytoss.igui.database.GuiTextureDatabase
import de.tytoss.igui.texture.GuiTexture
import de.tytoss.igui.texture.GuiTextureDefinition
import de.tytoss.igui.texture.IGuiObject
import de.tytoss.igui.texture.UnknownGuiTextureException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of compiled [GuiTexture]s backing [de.tytoss.igui.IGui]'s
 * texture lookups. Loaded textures, in-flight loads and known-missing ids
 * are all tracked so concurrent lookups for the same id share one load and
 * repeated lookups for a nonexistent id don't keep hitting the database
 * (see [de.tytoss.igui.IGuiMetrics.negativeCacheHits]).
 */
internal class TextureRegistry {
    private lateinit var metrics: MetricsCollector
    private val installed = ArrayList<IGuiObject>()
    private val textures = ConcurrentHashMap<String, GuiTexture>()
    private val loads = ConcurrentHashMap<String, Deferred<GuiTexture>>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Number of textures currently cached. */
    val size: Int get() = textures.size

    /**
     * One-time startup: warms the cache with [definitions] (already fetched
     * from the database by the caller), then binds each [IGuiObject] to its
     * texture by id. On failure, everything installed so far is rolled back.
     *
     * @param definitions textures to seed the cache with.
     * @param objects [IGuiObject]s to bind; each must reference an id present in [definitions].
     * @param metrics the metrics collector to record cache activity into.
     */
    fun install(
        definitions: List<GuiTextureDefinition>,
        objects: List<IGuiObject>,
        metrics: MetricsCollector,
    ) {
        this.metrics = metrics
        val identities = Collections.newSetFromMap(IdentityHashMap<IGuiObject, Boolean>())
        require(objects.all(identities::add)) { "The same IGuiObject cannot be registered twice" }

        try {
            definitions.forEach { definition ->
                require(!textures.containsKey(definition.id)) {
                    "Duplicate texture id '${definition.id}'"
                }
                put(definition)
            }
            objects.forEach { objectRef ->
                check(objectRef.attachment == null) { "Texture '${objectRef.id}' is already installed" }
                val texture = textures[objectRef.id]
                    ?: throw IllegalArgumentException("Texture '${objectRef.id}' was not found in the database")
                objectRef.attachment = texture
                installed += objectRef
            }
        } catch (exception: Exception) {
            installed.forEach { it.attachment = null }
            installed.clear()
            textures.clear()
            loads.clear()
            missing.clear()
            throw exception
        }
    }

    /**
     * Resolves an already-cached texture synchronously.
     *
     * @param id the texture id.
     * @return the cached texture.
     * @throws IllegalArgumentException if the texture is not currently cached.
     */
    fun cached(id: String): GuiTexture = textures[id]
        ?: throw IllegalArgumentException("Texture '$id' was not found in the database")

    /**
     * Resolves a texture, loading it from [database] on a cache miss.
     * Concurrent calls for the same [id] share a single in-flight load.
     *
     * @param id the texture id.
     * @param database the database to load from on a cache miss.
     * @param scope scope the (cancellable, shareable) load coroutine runs in.
     * @return the loaded texture.
     * @throws UnknownGuiTextureException if no texture with that id exists;
     *  the failure is cached so repeated lookups don't re-query the database.
     */
    suspend fun get(
        id: String,
        database: GuiTextureDatabase,
        scope: CoroutineScope,
    ): GuiTexture {
        require(id.isNotBlank()) { "Texture id must not be blank" }
        loads[id]?.let {
            if (id in missing) metrics.negativeCacheHit() else metrics.cacheHit()
            return it.await()
        }
        metrics.cacheMiss()
        val created = scope.async(start = CoroutineStart.LAZY) {
            val started = System.nanoTime()
            val definition = try {
                database.texture(id).also { metrics.databaseQuery(started, failed = false) }
            } catch (exception: Exception) {
                metrics.databaseQuery(started, failed = true)
                throw exception
            } ?: throw UnknownGuiTextureException(id)
            TextureCompiler.compile(definition).also { texture ->
                textures[id] = texture
                missing.remove(id)
            }
        }
        val loading = loads.putIfAbsent(id, created) ?: created.also(Deferred<GuiTexture>::start)
        if (loading !== created) created.cancel()
        return try {
            loading.await()
        } catch (exception: Exception) {
            if (exception is UnknownGuiTextureException) missing += id else loads.remove(id, loading)
            throw exception
        }
    }

    /**
     * Forces a fresh database load for [id], discarding any cached value or
     * in-flight load first.
     *
     * @param id the texture id to reload.
     * @param database the database to load from.
     * @param scope scope the load coroutine runs in.
     * @return the freshly loaded texture.
     * @throws UnknownGuiTextureException if the texture no longer exists.
     */
    suspend fun reload(
        id: String,
        database: GuiTextureDatabase,
        scope: CoroutineScope,
    ): GuiTexture {
        textures.remove(id)
        loads.remove(id)?.cancel()
        return get(id, database, scope)
    }

    /**
     * Compiles and caches a texture definition immediately, updating any
     * [IGuiObject] bound to the same id.
     *
     * @param definition the definition to cache.
     * @return the compiled texture now in the cache.
     */
    fun put(definition: GuiTextureDefinition): GuiTexture {
        val texture = TextureCompiler.compile(definition)
        textures[definition.id] = texture
        missing.remove(definition.id)
        loads.put(definition.id, CompletableDeferred(texture))?.cancel()
        installed.filter { it.id == definition.id }.forEach { it.attachment = texture }
        return texture
    }

    /**
     * Evicts a texture from the cache and marks its id as known-missing, so
     * subsequent lookups fail fast with [UnknownGuiTextureException] instead
     * of re-querying the database. Also clears the id from any bound
     * [IGuiObject].
     *
     * @param id the texture id to remove.
     */
    fun remove(id: String) {
        textures.remove(id)
        missing += id
        val missingTexture = CompletableDeferred<GuiTexture>().apply {
            completeExceptionally(UnknownGuiTextureException(id))
        }
        loads.put(id, missingTexture)?.cancel()
        installed.filter { it.id == id }.forEach { it.attachment = null }
    }

    /**
     * Replaces the entire cache with freshly compiled [definitions] in one
     * step, updating every bound [IGuiObject].
     *
     * @param definitions the full, authoritative set of texture definitions.
     */
    fun replaceAll(definitions: List<GuiTextureDefinition>) {
        val replacements = definitions.associate { it.id to TextureCompiler.compile(it) }
        loads.values.forEach(Deferred<GuiTexture>::cancel)
        loads.clear()
        textures.clear()
        missing.clear()
        replacements.forEach { (id, texture) ->
            textures[id] = texture
            loads[id] = CompletableDeferred(texture)
        }
        installed.forEach { it.attachment = replacements[it.id] }
    }

    /** Detaches every bound [IGuiObject], cancels in-flight loads and clears the cache. */
    fun close() {
        installed.forEach { it.attachment = null }
        installed.clear()
        textures.clear()
        loads.values.forEach(Deferred<GuiTexture>::cancel)
        loads.clear()
        missing.clear()
    }
}
