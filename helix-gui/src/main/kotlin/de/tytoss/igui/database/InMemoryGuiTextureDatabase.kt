package de.tytoss.igui.database

import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local [GuiTextureDatabase] for plugins that ship their textures in
 * their own resource pack and declare them in code: nothing is persisted,
 * every [put]/[remove] is visible immediately and announced on [changes].
 *
 * Use it with `IGui.install { database(InMemoryGuiTextureDatabase()) ; texture(...) }`
 * when a plugin runs its own IGui instance instead of a shared, database-backed one.
 *
 * @param initial textures available from the start.
 */
class InMemoryGuiTextureDatabase(initial: Iterable<GuiTextureDefinition> = emptyList()) : GuiTextureDatabase {
    private val entries = ConcurrentHashMap<String, GuiTextureDefinition>()
    private val changeFlow = MutableSharedFlow<GuiTextureChange>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        initial.forEach { entries[it.id] = it }
    }

    /** All stored textures, in no particular order. */
    override suspend fun textures(): List<GuiTextureDefinition> = entries.values.toList()

    /**
     * Looks up one texture.
     *
     * @param id the texture id.
     * @return the definition, or `null` if unknown.
     */
    override suspend fun texture(id: String): GuiTextureDefinition? = entries[id]

    /**
     * Inserts or replaces a texture and announces the change.
     *
     * @param texture the definition to store.
     */
    override suspend fun put(texture: GuiTextureDefinition) {
        entries[texture.id] = texture
        changeFlow.tryEmit(GuiTextureChange(GuiTextureChangeType.UPSERT, texture.id))
    }

    /**
     * Removes a texture and announces the change.
     *
     * @param id the texture id.
     * @return `true` if a texture was removed.
     */
    override suspend fun remove(id: String): Boolean {
        val removed = entries.remove(id) != null
        if (removed) changeFlow.tryEmit(GuiTextureChange(GuiTextureChangeType.DELETE, id))
        return removed
    }

    /** Stream of changes made through this instance. */
    override fun changes(): Flow<GuiTextureChange> = changeFlow.asSharedFlow()

    /** Nothing to release. */
    override suspend fun close() = Unit

    private companion object {
        const val CHANGE_BUFFER = 64
    }
}
