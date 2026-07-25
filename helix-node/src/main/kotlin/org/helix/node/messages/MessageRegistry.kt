package org.helix.node.messages

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/**
 * Central registry of every owner's [MessageBundle], exposed to the
 * dashboard and the bridges as one flat translation namespace.
 *
 * A bundle key `screen.maintenance` of owner `velocity` appears as the flat
 * key `helix.translations.velocity.screen.maintenance`. Because owner ids may
 * contain dots (`helix.friends`), flat keys are parsed by longest-owner
 * match. Keys under an unregistered owner are rejected; free-form keys
 * belong under the always-registered `custom` owner.
 */
class MessageRegistry {
    private val bundles = ConcurrentHashMap<String, MessageBundle>()

    /**
     * Registers (or replaces) an owner's bundle.
     *
     * @param owner owning addon or subsystem id.
     * @param bundle the owner's message bundle.
     */
    fun register(owner: String, bundle: MessageBundle) {
        bundles[owner] = bundle
    }

    /**
     * Removes an owner's bundle, on disable.
     *
     * @param owner owning addon or subsystem id.
     */
    fun unregisterOwner(owner: String) {
        bundles.remove(owner)
    }

    /**
     * Looks up an owner's bundle.
     *
     * @param owner owning addon or subsystem id.
     * @return the bundle or `null`.
     */
    fun find(owner: String): MessageBundle? = bundles[owner]

    /**
     * Every translation as one flat, sorted list for the dashboard.
     *
     * @return entries with custom values and defaults per language.
     */
    fun entries(): List<TranslationEntry> =
        bundles.toSortedMap().flatMap { (owner, bundle) ->
            val custom = bundle.customValues()
            val defaults = bundle.defaultValues()
            bundle.keys().map { key ->
                TranslationEntry(
                    key = flatKey(owner, key),
                    values = custom.mapNotNull { (lang, entries) -> entries[key]?.let { lang to it } }.toMap(),
                    defaults = defaults.mapNotNull { (lang, entries) -> entries[key]?.let { lang to it } }.toMap(),
                )
            }
        }.sortedBy { it.key }

    /**
     * Effective flat translation tables for the bridges.
     *
     * @param languages the languages to build tables for.
     * @return language code to (flat key to template).
     */
    fun effectiveTables(languages: List<String>): Map<String, Map<String, String>> =
        languages.associateWith { language ->
            bundles.toSortedMap().flatMap { (owner, bundle) ->
                bundle.effective(language).map { (key, value) -> flatKey(owner, key) to value }
            }.toMap()
        }

    /**
     * Sets (or creates) a translation via its flat key.
     *
     * @param flatKey full key, for example
     *   `helix.translations.velocity.screen.maintenance`.
     * @param language language code.
     * @param value template text.
     * @return `true` if the owner exists and the value was stored.
     */
    fun set(flatKey: String, language: String, value: String): Boolean {
        val (owner, key) = parse(flatKey) ?: return false
        return bundles[owner]?.set(language, key, value) ?: false
    }

    /**
     * Removes a custom value, restoring the default of that language.
     *
     * @param flatKey full translation key.
     * @param language language code.
     * @return `true` if a custom value existed.
     */
    fun reset(flatKey: String, language: String): Boolean {
        val (owner, key) = parse(flatKey) ?: return false
        return bundles[owner]?.reset(language, key) ?: false
    }

    /**
     * Deletes a custom-created key across all languages.
     *
     * @param flatKey full translation key.
     * @return `true` if the key existed and had no declared default.
     */
    fun deleteKey(flatKey: String): Boolean {
        val (owner, key) = parse(flatKey) ?: return false
        return bundles[owner]?.deleteKey(key) ?: false
    }

    /**
     * The owner of a flat key.
     *
     * @param flatKey full translation key.
     * @return the owner id, or `null` for unknown owners.
     */
    fun ownerOf(flatKey: String): String? = parse(flatKey)?.first

    private fun parse(flatKey: String): Pair<String, String>? {
        val remainder = flatKey.removePrefix(FLAT_PREFIX)
        if (remainder == flatKey) {
            return null
        }
        val owner = bundles.keys
            .filter { remainder.startsWith("$it.") }
            .maxByOrNull { it.length }
            ?: return null
        return owner to remainder.removePrefix("$owner.")
    }

    /** Namespace shared by every translation key. */
    companion object {
        /** Prefix of every flat translation key. */
        const val FLAT_PREFIX = "helix.translations."

        /**
         * Builds the flat key of an owner's message key.
         *
         * @param owner owning addon or subsystem id.
         * @param key bundle-local message key.
         * @return the flat `helix.translations.<owner>.<key>` key.
         */
        fun flatKey(owner: String, key: String): String = "$FLAT_PREFIX$owner.$key"
    }
}

/**
 * One translation key with its values and defaults per language.
 *
 * @property key flat translation key.
 * @property values custom (persisted) values: language code to template.
 * @property defaults declared defaults: language code to template.
 */
@Serializable
data class TranslationEntry(
    val key: String,
    val values: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
)
