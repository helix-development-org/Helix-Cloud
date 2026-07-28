package org.helix.addons.profile.paper

import de.tytoss.igui.database.GuiTextureDatabase
import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.serialization.json.Json

/**
 * [GuiTextureDatabase] backed by the profile addon's `profile.texture.*`
 * actions instead of a direct database connection — this plugin runs on a
 * game server and, like every other Paper-side component in this
 * platform, only ever talks to the node over its action HTTP contract.
 *
 * @property client talks to the node on behalf of this database.
 */
class NodeGuiTextureDatabase(private val client: ProfileNodeClient) : GuiTextureDatabase {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun textures(): List<GuiTextureDefinition> {
        val listJson = client.textureListJson() ?: return emptyList()
        return runCatching { json.decodeFromString<List<TextureRecordJson>>(listJson) }
            .getOrDefault(emptyList())
            .map { it.toDefinition() }
    }

    override suspend fun texture(id: String): GuiTextureDefinition? =
        client.textureGetJson(id)
            ?.let { runCatching { json.decodeFromString<TextureRecordJson>(it) }.getOrNull() }
            ?.toDefinition()

    override suspend fun put(texture: GuiTextureDefinition) {
        client.texturePut(texture.id, json.encodeToString(TextureRecordJson.from(texture)))
    }

    override suspend fun remove(id: String): Boolean = client.textureRemove(id)

    override suspend fun close() {
        // Nothing to release: every call already opens and closes its own HTTP request.
    }
}
