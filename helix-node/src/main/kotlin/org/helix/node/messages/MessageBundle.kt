package org.helix.node.messages

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.helix.api.message.Messages
import org.helix.api.message.applyPlaceholders

/**
 * An addon's configurable messages, persisted as `messages.json` in its
 * data directory.
 *
 * On construction the defaults seed the file: missing keys are added and
 * persisted, existing values are kept. Reads always reflect the current
 * (possibly dashboard-edited) values.
 *
 * @property file the `messages.json` path.
 * @property defaults default templates keyed by message key.
 */
class MessageBundle(
    private val file: Path,
    private val defaults: Map<String, String>,
) : Messages {
    private val json = Json { prettyPrint = true }
    private val values = linkedMapOf<String, String>()

    init {
        if (Files.exists(file)) {
            runCatching { json.decodeFromString<Map<String, String>>(Files.readString(file)) }
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
        if (changed || Files.notExists(file)) {
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
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(values.toMap()))
    }
}
