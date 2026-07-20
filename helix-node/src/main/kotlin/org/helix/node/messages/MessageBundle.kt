package org.helix.node.messages

import kotlinx.serialization.json.Json
import org.helix.api.message.Messages
import org.helix.api.message.applyPlaceholders
import org.helix.api.storage.AddonStorage

/**
 * An addon's configurable messages, persisted through the addon's document
 * storage (files or PostgreSQL) under the `messages` key.
 *
 * On construction the defaults seed storage: missing keys are added and
 * persisted, existing values are kept. Reads always reflect the current
 * (possibly dashboard-edited) values.
 *
 * @property storage addon-scoped document store.
 * @property defaults default templates keyed by message key.
 */
class MessageBundle(
    private val storage: AddonStorage,
    private val defaults: Map<String, String>,
) : Messages {
    private val json = Json { prettyPrint = true }
    private val values = linkedMapOf<String, String>()

    init {
        val existing = storage.read(DOCUMENT)
        existing?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }
                .getOrDefault(emptyMap())
                .forEach { (key, value) -> values[key] = value }
        }
        var changed = false
        defaults.forEach { (key, value) ->
            if (!values.containsKey(key)) {
                values[key] = value
                changed = true
            }
        }
        if (changed || existing == null) {
            persist()
        }
    }

    /**
     * Formats a message with placeholder substitution.
     *
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted message.
     */
    @Synchronized
    override fun format(key: String, vararg params: Pair<String, String>): String =
        applyPlaceholders(values[key] ?: defaults[key] ?: key, params)

    /**
     * Returns the raw current template.
     *
     * @param key message key.
     * @return the template.
     */
    @Synchronized
    override fun raw(key: String): String = values[key] ?: defaults[key] ?: key

    /**
     * Current values, key insertion order preserved.
     *
     * @return message key to current template.
     */
    @Synchronized
    fun all(): Map<String, String> = values.toMap()

    /**
     * The default template of a key, if declared.
     *
     * @param key message key.
     * @return the default or `null`.
     */
    fun default(key: String): String? = defaults[key]

    /**
     * Overwrites a known message and persists it.
     *
     * @param key message key; must be a declared key.
     * @param value new template.
     * @return `true` if the key was known and updated.
     */
    @Synchronized
    fun set(key: String, value: String): Boolean {
        if (!values.containsKey(key)) {
            return false
        }
        values[key] = value
        persist()
        return true
    }

    /**
     * Resets a message to its declared default.
     *
     * @param key message key.
     * @return `true` if a default existed and was restored.
     */
    @Synchronized
    fun reset(key: String): Boolean {
        val fallback = defaults[key] ?: return false
        values[key] = fallback
        persist()
        return true
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(values.toMap()))
    }

    private companion object {
        /** Document key holding the message map. */
        const val DOCUMENT = "messages"
    }
}
