package org.helix.addons.citybuild.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.helix.api.storage.AddonStorage

/**
 * One JSON document of a configuration collection, for example a ware in `adminshop`.
 *
 * @property id document id, unique within its collection.
 * @property sortOrder ordering hint (ascending, then id); the order is the display order.
 * @property body the structured payload.
 */
@Serializable
data class ConfigDocument(val id: String, val sortOrder: Int = 0, val body: JsonObject)

/**
 * Persisted shape of the settings document.
 *
 * @property values key to normalized value.
 * @property version monotonic version, bumped on every change of settings or collections.
 */
@Serializable
data class SettingsDocument(val values: Map<String, String> = emptyMap(), val version: Long = 0)

/**
 * Persisted shape of one collection document.
 *
 * @property documents the documents of the collection.
 */
@Serializable
data class CollectionDocument(val documents: List<ConfigDocument> = emptyList())

/**
 * Persisted bookkeeping of the seed upgrades.
 *
 * @property version seed version ([ConfigSeeds.SEED_VERSION]) the stored configuration was last upgraded to.
 * @property seeded `collection/id` of every seed document ever inserted; a seeded document that is missing
 *  later was deleted by an operator and is not inserted again.
 */
@Serializable
data class SeedState(val version: Int = 0, val seeded: Set<String> = emptySet())

/**
 * Configuration of CityBuild — typed settings plus document collections — persisted in the addon
 * storage and served to the Paper servers as one bundle. Missing seeds are inserted on load so a
 * new key becomes editable without wiping operator changes. All public methods are synchronized.
 *
 * @property storage the addon-scoped document store.
 */
class ConfigStore(private val storage: AddonStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var settings: SettingsDocument = storage.read(SETTINGS_KEY)?.let { json.decodeFromString<SettingsDocument>(it) } ?: SettingsDocument()
    private val collections = linkedMapOf<String, MutableList<ConfigDocument>>()

    init {
        storage.keys().filter { it.startsWith(COLLECTION_PREFIX) }.forEach { key ->
            val name = key.removePrefix(COLLECTION_PREFIX)
            collections[name] = storage.read(key)?.let { json.decodeFromString<CollectionDocument>(it).documents.toMutableList() } ?: mutableListOf()
        }
        seed(force = false)
    }

    /** Current configuration version (bumped on every write). */
    @Synchronized
    fun version(): Long = settings.version

    /**
     * Returns a setting value.
     *
     * @param key setting key.
     * @return the value or `null` when unknown.
     */
    @Synchronized
    fun setting(key: String): String? = settings.values[key]

    /** All settings sorted by key. */
    @Synchronized
    fun settings(): Map<String, String> = settings.values.toSortedMap()

    /**
     * Sets a setting; the value is validated against the seed definition when one exists.
     *
     * @param key setting key.
     * @param raw the new raw value.
     * @return the normalized value.
     * @throws IllegalArgumentException when the value does not match the definition's type.
     */
    @Synchronized
    fun setSetting(key: String, raw: String): String {
        require(key.isNotBlank()) { "setting key must not be blank" }
        val value = ConfigSeeds.settings.find { it.key == key }?.normalize(raw) ?: raw.trim()
        settings = settings.copy(values = settings.values + (key to value), version = settings.version + 1)
        storage.write(SETTINGS_KEY, json.encodeToString(settings))
        return value
    }

    /**
     * Resets a setting to its seed default.
     *
     * @param key setting key.
     * @return the default, or `null` when the key has no seed definition (nothing changes).
     */
    @Synchronized
    fun resetSetting(key: String): String? {
        val definition = ConfigSeeds.settings.find { it.key == key } ?: return null
        return setSetting(key, definition.default)
    }

    /** Names of all collections, sorted. */
    @Synchronized
    fun collectionNames(): List<String> = collections.keys.sorted()

    /**
     * Documents of a collection ordered by sort order and id.
     *
     * @param collection collection name.
     * @return the documents (empty when unknown).
     */
    @Synchronized
    fun documents(collection: String): List<ConfigDocument> =
        collections[collection].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /**
     * Returns one document.
     *
     * @param collection collection name.
     * @param id document id.
     * @return the document or `null`.
     */
    @Synchronized
    fun document(collection: String, id: String): ConfigDocument? = collections[collection]?.firstOrNull { it.id == id }

    /**
     * Creates or replaces a document and bumps the version.
     *
     * @param collection collection name.
     * @param document the document.
     */
    @Synchronized
    fun putDocument(collection: String, document: ConfigDocument) {
        require(collection.isNotBlank() && document.id.isNotBlank()) { "collection and id must not be blank" }
        val list = collections.getOrPut(collection) { mutableListOf() }
        list.removeAll { it.id == document.id }
        list += document
        persistCollection(collection)
        bump()
    }

    /**
     * Removes a document.
     *
     * @param collection collection name.
     * @param id document id.
     * @return `true` when a document was removed.
     */
    @Synchronized
    fun removeDocument(collection: String, id: String): Boolean {
        val removed = collections[collection]?.removeAll { it.id == id } == true
        if (removed) {
            persistCollection(collection)
            bump()
        }
        return removed
    }

    /**
     * Brings the stored configuration up to the current seeds without destroying operator changes:
     * missing settings are inserted and untouched superseded defaults ([ConfigSeeds.supersededDefaults])
     * lifted; seed documents never inserted before are added (documents an operator deleted stay
     * deleted); when the stored seed version is older than [ConfigSeeds.SEED_VERSION], fields a seed
     * document gained are added to the stored document — existing values are never overwritten.
     * With [force] every seed overwrites the stored value instead.
     *
     * @param force overwrite existing values.
     * @return number of written entries (settings, documents and merged fields).
     */
    @Synchronized
    fun seed(force: Boolean): Int {
        var written = 0
        val values = settings.values.toMutableMap()
        for (definition in ConfigSeeds.settings) {
            val stale = values[definition.key]?.let { it in ConfigSeeds.supersededDefaults[definition.key].orEmpty() } == true
            if (force || definition.key !in values || stale) {
                values[definition.key] = definition.default
                written++
            }
        }
        val state = storage.read(SEED_STATE_KEY)?.let { json.decodeFromString<SeedState>(it) }
        val seeded = state?.seeded?.toMutableSet() ?: mutableSetOf()
        val upgrade = (state?.version ?: 0) < ConfigSeeds.SEED_VERSION
        for ((name, documents) in ConfigSeeds.collections) {
            val list = collections.getOrPut(name) { mutableListOf() }
            var changed = false
            for (document in documents) {
                val key = "$name/${document.id}"
                val index = list.indexOfFirst { it.id == document.id }
                when {
                    force || (index < 0 && (state == null || key !in seeded)) -> {
                        if (index >= 0) list.removeAt(index)
                        list += document
                        written++
                        changed = true
                    }
                    index >= 0 && upgrade -> {
                        val merged = mergeMissing(list[index].body, document.body)
                        if (merged.second > 0) {
                            list[index] = list[index].copy(body = merged.first)
                            written += merged.second
                            changed = true
                        }
                    }
                }
                seeded += key
            }
            if (changed) persistCollection(name)
        }
        storage.write(SEED_STATE_KEY, json.encodeToString(SeedState(ConfigSeeds.SEED_VERSION, seeded)))
        if (written > 0) {
            settings = SettingsDocument(values, settings.version + 1)
            storage.write(SETTINGS_KEY, json.encodeToString(settings))
        }
        return written
    }

    /**
     * Renders the whole configuration as the bundle JSON consumed by the CityBuild Paper servers:
     * `{"version":…,"settings":{…},"collections":{"adminshop":[{"id","sortOrder","body"}]}}`.
     *
     * @return compact JSON text.
     */
    @Synchronized
    fun bundleJson(): String = buildJsonObject {
        put("version", settings.version)
        put("settings", buildJsonObject { settings.values.toSortedMap().forEach { (k, v) -> put(k, v) } })
        put("collections", buildJsonObject {
            collectionNames().forEach { name ->
                put(name, buildJsonArray {
                    documents(name).forEach { doc ->
                        add(buildJsonObject { put("id", doc.id); put("sortOrder", doc.sortOrder); put("body", doc.body) })
                    }
                })
            }
        })
    }.toString()

    /**
     * Renders the settings with their definitions for the dashboard panel.
     *
     * @return JSON array of `{key, value, type, default, description}`.
     */
    @Synchronized
    fun settingsExportJson(): String = buildJsonArray {
        settings.values.toSortedMap().forEach { (key, value) ->
            val definition = ConfigSeeds.settings.find { it.key == key }
            add(buildJsonObject {
                put("key", key); put("value", value)
                put("type", definition?.type ?: "STRING"); put("default", definition?.default ?: JsonPrimitive(null).content)
                put("description", definition?.description ?: "")
            })
        }
    }.toString()

    /**
     * Adds every field of [seed] that [stored] lacks; nested objects are merged recursively, all
     * other values (including arrays) of [stored] win.
     *
     * @return the merged object and the number of added fields.
     */
    private fun mergeMissing(stored: JsonObject, seed: JsonObject): Pair<JsonObject, Int> {
        var added = 0
        val result = LinkedHashMap(stored)
        for ((key, value) in seed) {
            val current = result[key]
            when {
                current == null -> { result[key] = value; added++ }
                current is JsonObject && value is JsonObject -> {
                    val (merged, count) = mergeMissing(current, value)
                    if (count > 0) { result[key] = merged; added += count }
                }
            }
        }
        return JsonObject(result) to added
    }

    private fun bump() {
        settings = settings.copy(version = settings.version + 1)
        storage.write(SETTINGS_KEY, json.encodeToString(settings))
    }

    private fun persistCollection(name: String) {
        storage.write(COLLECTION_PREFIX + name, json.encodeToString(CollectionDocument(collections[name].orEmpty())))
    }

    private companion object {
        const val SETTINGS_KEY = "config.settings"
        const val COLLECTION_PREFIX = "config.collection."
        const val SEED_STATE_KEY = "config.seeds"
    }
}
