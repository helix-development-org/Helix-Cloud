package de.tytoss.iguard.gui

import de.tytoss.igui.database.GuiTextureDatabase
import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.kyori.adventure.key.Key
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-backed [GuiTextureDatabase] for helix mode, so the admin panel works without the PostgreSQL
 * setup IGui offers as default: texture definitions live in a small JSON file inside the plugin data
 * folder. IGuard registers its glyphs on every install anyway, so the file is just a warm cache.
 */
class FileGuiTextureDatabase(private val file: Path) : GuiTextureDatabase {
    private val lock = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val entries = linkedMapOf<String, GuiTextureDefinition>()

    init {
        if (Files.exists(file)) {
            runCatching { json.parseToJsonElement(Files.readString(file)).jsonArray }
                .getOrNull()
                ?.forEach { element ->
                    runCatching { element.jsonObject.toDefinition() }.getOrNull()?.let { entries[it.id] = it }
                }
        }
    }

    /** Lists all stored definitions in insertion order. */
    override suspend fun textures(): List<GuiTextureDefinition> = synchronized(lock) { entries.values.toList() }

    /** Looks up one definition by [id], or null. */
    override suspend fun texture(id: String): GuiTextureDefinition? = synchronized(lock) { entries[id] }

    /** Stores or replaces a definition and persists the file. */
    override suspend fun put(texture: GuiTextureDefinition) {
        synchronized(lock) {
            entries[texture.id] = texture
            persist()
        }
    }

    /** Removes a definition; returns true when it existed. */
    override suspend fun remove(id: String): Boolean = synchronized(lock) {
        val removed = entries.remove(id) != null
        if (removed) persist()
        removed
    }

    /** No pooled resources to release. */
    override suspend fun close() = Unit

    private fun persist() {
        file.parent?.let(Files::createDirectories)
        val array = buildJsonArray {
            entries.values.forEach { definition ->
                add(
                    buildJsonObject {
                        put("id", definition.id)
                        put("character", definition.character)
                        put("font", definition.font.asString())
                        put("widthPixels", definition.widthPixels)
                        put("heightPixels", definition.heightPixels)
                        put("advancePixels", definition.advancePixels)
                    }
                )
            }
        }
        Files.writeString(file, json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), array))
    }

    private fun kotlinx.serialization.json.JsonObject.toDefinition(): GuiTextureDefinition = GuiTextureDefinition(
        id = get("id")?.jsonPrimitive?.contentOrNull ?: error("id missing"),
        character = get("character")?.jsonPrimitive?.contentOrNull ?: error("character missing"),
        font = Key.key(get("font")?.jsonPrimitive?.contentOrNull ?: "minecraft:default"),
        widthPixels = get("widthPixels")?.jsonPrimitive?.intOrNull ?: error("widthPixels missing"),
        heightPixels = get("heightPixels")?.jsonPrimitive?.intOrNull ?: 18,
        advancePixels = get("advancePixels")?.jsonPrimitive?.intOrNull
            ?: ((get("widthPixels")?.jsonPrimitive?.intOrNull ?: 0) + 1),
    )
}
