package org.helix.addons.guis.paper

import de.tytoss.igui.database.GuiTextureDatabase
import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * [GuiTextureDatabase] backed by the guis addon's `guis.texture.*` actions
 * instead of a direct database connection — this plugin runs on a game
 * server and, like every other Paper-side component in this platform, only
 * ever talks to the node over its action HTTP contract. All calls hop onto
 * [Dispatchers.IO]: the underlying HTTP client blocks, and IGui invokes
 * this database from its own (default-dispatcher) coroutine scope.
 *
 * @property client talks to the node on behalf of this database.
 */
class NodeGuiTextureDatabase(private val client: GuisNodeClient) : GuiTextureDatabase {
    private val logger = LoggerFactory.getLogger(NodeGuiTextureDatabase::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun textures(): List<GuiTextureDefinition> = withContext(Dispatchers.IO) {
        val listJson = client.textureListJson() ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<TextureRecordJson>>(listJson) }
            .getOrDefault(emptyList())
            .map { it.toDefinition() }
    }

    override suspend fun texture(id: String): GuiTextureDefinition? = withContext(Dispatchers.IO) {
        client.textureGetJson(id)
            ?.let { runCatching { json.decodeFromString<TextureRecordJson>(it) }.getOrNull() }
            ?.toDefinition()
    }

    override suspend fun put(texture: GuiTextureDefinition) {
        val stored = withContext(Dispatchers.IO) {
            client.texturePut(texture.id, json.encodeToString(TextureRecordJson.from(texture)))
        }
        if (!stored) {
            logger.warn("Storing GUI texture '{}' on the node failed — it will be re-registered on next enable", texture.id)
        }
    }

    override suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) { client.textureRemove(id) }

    override suspend fun close() {
        // Nothing to release: every call already opens and closes its own HTTP request.
    }
}
