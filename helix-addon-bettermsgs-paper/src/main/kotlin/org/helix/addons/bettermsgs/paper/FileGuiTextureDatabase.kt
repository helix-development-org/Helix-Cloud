package org.helix.addons.bettermsgs.paper

import de.tytoss.igui.database.GuiTextureDatabase
import de.tytoss.igui.texture.GuiTextureDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kyori.adventure.key.Key

/**
 * File-backed [GuiTextureDatabase], so BetterMSGs works without the
 * PostgreSQL setup IGui offers as default.
 *
 * @property file JSON file inside the plugin data folder.
 */
class FileGuiTextureDatabase(private val file: Path) : GuiTextureDatabase {
    private val lock = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val entries = linkedMapOf<String, GuiTextureDefinition>()

    init {
        if (Files.exists(file)) {
            runCatching { json.decodeFromString<List<StoredTexture>>(Files.readString(file)) }
                .getOrDefault(emptyList())
                .forEach { entries[it.id] = it.toDefinition() }
        }
    }

    /**
     * Lists all stored definitions.
     *
     * @return the definitions in insertion order.
     */
    override suspend fun textures(): List<GuiTextureDefinition> = synchronized(lock) { entries.values.toList() }

    /**
     * Looks up one definition.
     *
     * @param id texture id.
     * @return the definition or `null`.
     */
    override suspend fun texture(id: String): GuiTextureDefinition? = synchronized(lock) { entries[id] }

    /**
     * Stores or replaces a definition.
     *
     * @param texture the definition.
     */
    override suspend fun put(texture: GuiTextureDefinition) {
        synchronized(lock) {
            entries[texture.id] = texture
            persist()
        }
    }

    /**
     * Removes a definition.
     *
     * @param id texture id.
     * @return `true` when it existed.
     */
    override suspend fun remove(id: String): Boolean = synchronized(lock) {
        val removed = entries.remove(id) != null
        if (removed) {
            persist()
        }
        removed
    }

    /**
     * No pooled resources to release.
     */
    override suspend fun close() = Unit

    private fun persist() {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(entries.values.map(::StoredTexture)))
    }

    /**
     * Serializable mirror of [GuiTextureDefinition].
     *
     * @property id texture id.
     * @property character glyph character.
     * @property font font key as string.
     * @property widthPixels rendered width.
     * @property heightPixels rendered height.
     * @property advancePixels cursor advance.
     */
    @Serializable
    private data class StoredTexture(
        val id: String,
        val character: String,
        val font: String,
        val widthPixels: Int,
        val heightPixels: Int,
        val advancePixels: Int,
    ) {
        constructor(definition: GuiTextureDefinition) : this(
            definition.id,
            definition.character,
            definition.font.asString(),
            definition.widthPixels,
            definition.heightPixels,
            definition.advancePixels,
        )

        /**
         * Converts back into the IGui definition.
         *
         * @return the definition.
         */
        fun toDefinition(): GuiTextureDefinition = GuiTextureDefinition(
            id = id,
            character = character,
            font = Key.key(font),
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            advancePixels = advancePixels,
        )
    }
}
