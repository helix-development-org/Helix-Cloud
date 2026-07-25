package org.helix.node.messages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.api.message.Messages
import org.helix.api.message.applyPlaceholders
import org.helix.api.storage.AddonStorage

/**
 * An owner's configurable, multilingual messages, persisted through the
 * owner's document storage under the `messages` key.
 *
 * Defaults are declared per language in code and never persisted; storage
 * holds only custom (panel-edited or panel-created) values, as
 * `{language: {key: value}}`. Resolution overlays custom values on the
 * defaults of the requested language, then falls back to the network's
 * default language, then to the key itself. Documents written by the
 * pre-multilingual format (`{key: value}`) are migrated on load: entries
 * that differ from the English default become English custom values.
 *
 * @property storage owner-scoped document store.
 * @property defaults default templates: language code to (key to template).
 * @property defaultLanguage supplier of the network's default language.
 */
class MessageBundle(
    private val storage: AddonStorage,
    private val defaults: Map<String, Map<String, String>>,
    private val defaultLanguage: () -> String = { MIGRATION_LANGUAGE },
    languageOf: ((String) -> String)? = null,
) : Messages {
    private val json = Json { prettyPrint = true }
    private val languageOf: (String) -> String = languageOf ?: { defaultLanguage() }
    private val custom = linkedMapOf<String, LinkedHashMap<String, String>>()

    init {
        val raw = storage.read(DOCUMENT)
        if (raw != null) {
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (root != null && root.values.all { it is JsonObject }) {
                loadCurrentFormat(root)
            } else if (root != null) {
                migrateLegacyFormat(root)
            }
        }
    }

    private fun loadCurrentFormat(root: JsonObject) {
        root.forEach { (language, entries) ->
            (entries as JsonObject).forEach { (key, value) ->
                if (value is JsonPrimitive) {
                    custom.getOrPut(language) { linkedMapOf() }[key] = value.jsonPrimitive.content
                }
            }
        }
    }

    private fun migrateLegacyFormat(root: JsonObject) {
        val englishDefaults = defaults[MIGRATION_LANGUAGE].orEmpty()
        root.forEach { (key, value) ->
            if (value is JsonPrimitive && value.jsonPrimitive.content != englishDefaults[key]) {
                custom.getOrPut(MIGRATION_LANGUAGE) { linkedMapOf() }[key] = value.jsonPrimitive.content
            }
        }
        persist()
    }

    /**
     * Formats a message in the network's default language.
     *
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted message, or the key itself if unknown.
     */
    @Synchronized
    override fun format(key: String, vararg params: Pair<String, String>): String =
        applyPlaceholders(rawIn(defaultLanguage(), key), params)

    /**
     * Formats a message in the player's language.
     *
     * @param player receiving player name.
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted message, or the key itself if unknown.
     */
    @Synchronized
    override fun formatFor(player: String, key: String, vararg params: Pair<String, String>): String =
        applyPlaceholders(rawIn(languageOf(player), key), params)

    /**
     * The raw template in the network's default language.
     *
     * @param key message key.
     * @return the template, or the key itself if unknown.
     */
    @Synchronized
    override fun raw(key: String): String = rawIn(defaultLanguage(), key)

    /**
     * The raw template in the player's language.
     *
     * @param player receiving player name.
     * @param key message key.
     * @return the template, or the key itself if unknown.
     */
    @Synchronized
    override fun rawFor(player: String, key: String): String = rawIn(languageOf(player), key)

    /**
     * The raw template in a specific language.
     *
     * Falls back to the network default language, then English, then any
     * language holding the key — so a value never becomes unreachable after
     * a default-language switch.
     *
     * @param language language code.
     * @param key message key.
     * @return the template, or the key itself if unknown.
     */
    @Synchronized
    fun rawIn(language: String, key: String): String =
        resolve(language, key)
            ?: resolve(defaultLanguage(), key)
            ?: resolve(MIGRATION_LANGUAGE, key)
            ?: firstAvailable(key)
            ?: key

    private fun resolve(language: String, key: String): String? =
        custom[language]?.get(key) ?: defaults[language]?.get(key)

    private fun firstAvailable(key: String): String? =
        custom.values.firstNotNullOfOrNull { it[key] }
            ?: defaults.values.firstNotNullOfOrNull { it[key] }

    /**
     * Every known key: declared defaults plus custom-created ones.
     *
     * @return sorted keys.
     */
    @Synchronized
    fun keys(): List<String> =
        (defaults.values.flatMap { it.keys } + custom.values.flatMap { it.keys }).distinct().sorted()

    /**
     * The custom (persisted) values.
     *
     * @return language code to (key to value).
     */
    @Synchronized
    fun customValues(): Map<String, Map<String, String>> = custom.mapValues { it.value.toMap() }

    /**
     * The declared defaults.
     *
     * @return language code to (key to template).
     */
    fun defaultValues(): Map<String, Map<String, String>> = defaults

    /**
     * Effective values of one language: defaults overlaid with custom
     * values, without cross-language fallback.
     *
     * @param language language code.
     * @return key to template.
     */
    @Synchronized
    fun effective(language: String): Map<String, String> =
        (defaults[language].orEmpty() + custom[language].orEmpty())

    /**
     * Whether a key has a declared default in any language.
     *
     * @param key message key.
     * @return `true` if a default exists.
     */
    fun hasDefault(key: String): Boolean = defaults.values.any { key in it }

    /**
     * Sets (or creates) a message value in one language and persists it.
     *
     * @param language language code.
     * @param key message key; new keys are allowed.
     * @param value template text.
     * @return `true` if stored.
     */
    @Synchronized
    fun set(language: String, key: String, value: String): Boolean {
        if (language.isBlank() || key.isBlank()) {
            return false
        }
        custom.getOrPut(language) { linkedMapOf() }[key] = value
        persist()
        return true
    }

    /**
     * Removes the custom value of one language, restoring the default.
     *
     * @param language language code.
     * @param key message key.
     * @return `true` if a custom value existed and was removed.
     */
    @Synchronized
    fun reset(language: String, key: String): Boolean {
        val removed = custom[language]?.remove(key) != null
        if (removed) {
            if (custom[language]?.isEmpty() == true) {
                custom.remove(language)
            }
            persist()
        }
        return removed
    }

    /**
     * Deletes a custom-created key across all languages.
     *
     * Keys with a declared default cannot be deleted, only [reset].
     *
     * @param key message key.
     * @return `true` if the key existed and was deleted.
     */
    @Synchronized
    fun deleteKey(key: String): Boolean {
        if (hasDefault(key)) {
            return false
        }
        var removed = false
        custom.values.forEach { entries -> removed = (entries.remove(key) != null) || removed }
        custom.entries.removeIf { it.value.isEmpty() }
        if (removed) {
            persist()
        }
        return removed
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(custom.mapValues { it.value.toMap() }))
    }

    private companion object {
        /** Document key holding the message map. */
        const val DOCUMENT = "messages"

        /** Language legacy single-language documents are migrated into. */
        const val MIGRATION_LANGUAGE = "en"
    }
}
