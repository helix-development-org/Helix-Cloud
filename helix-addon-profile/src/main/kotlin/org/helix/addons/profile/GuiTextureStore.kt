package org.helix.addons.profile

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Node-side persistence for Paper IGui menus' custom texture definitions.
 *
 * Backs a `de.tytoss.igui.database.GuiTextureDatabase` implementation on
 * the Paper side that talks to this store only through the profile
 * addon's actions (`profile.texture.*`) — never a direct database
 * connection from a game server, the same rule every other addon in this
 * platform follows for its own storage.
 *
 * @property storage addon-scoped document store.
 */
class GuiTextureStore(private val storage: AddonStorage) {
    private val json = Json { prettyPrint = true }
    private val textures = linkedMapOf<String, GuiTextureRecord>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            json.decodeFromString<List<GuiTextureRecord>>(raw).forEach { textures[it.id] = it }
        }
    }

    /**
     * All stored texture definitions.
     *
     * @return every stored definition.
     */
    @Synchronized
    fun all(): List<GuiTextureRecord> = textures.values.toList()

    /**
     * One stored texture definition.
     *
     * @param id the texture id.
     * @return the definition, or `null` if none exists with that id.
     */
    @Synchronized
    fun get(id: String): GuiTextureRecord? = textures[id]

    /**
     * Inserts or replaces a texture definition.
     *
     * @param record the definition to store.
     */
    @Synchronized
    fun put(record: GuiTextureRecord) {
        textures[record.id] = record
        persist()
    }

    /**
     * Removes a texture definition.
     *
     * @param id the texture id.
     * @return `true` if a definition with that id existed and was removed.
     */
    @Synchronized
    fun remove(id: String): Boolean {
        val removed = textures.remove(id) != null
        if (removed) {
            persist()
        }
        return removed
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(textures.values.toList()))
    }

    private companion object {
        /** Document key holding every stored texture definition. */
        const val DOCUMENT = "gui-textures"
    }
}
