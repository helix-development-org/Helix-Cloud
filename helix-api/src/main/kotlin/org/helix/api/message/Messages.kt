package org.helix.api.message

/**
 * Live, configurable message templates of an addon.
 *
 * An addon declares default templates once (via
 * [org.helix.api.addon.AddonContext.messages]); the node persists them in
 * the addon's data directory and lets operators edit them through the
 * dashboard. Reads always return the current value, so edits take effect
 * without restarting the addon.
 *
 * Templates use `{placeholder}` markers and may contain `&` color codes.
 */
interface Messages {
    /**
     * Formats a message, substituting `{placeholder}` markers.
     *
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted message, or the key itself if unknown.
     */
    fun format(key: String, vararg params: Pair<String, String>): String

    /**
     * Returns the raw template without substitution.
     *
     * @param key message key.
     * @return the template, or the key itself if unknown.
     */
    fun raw(key: String): String
}

/**
 * Substitutes `{name}` placeholders in a template.
 *
 * @param template the message template.
 * @param params placeholder name to value pairs.
 * @return the template with every `{name}` replaced.
 */
fun applyPlaceholders(template: String, params: Array<out Pair<String, String>>): String {
    var result = template
    params.forEach { (name, value) -> result = result.replace("{$name}", value) }
    return result
}

/**
 * [Messages] backed by a fixed map, used as the default when no node
 * persistence is available (for example in tests).
 *
 * @property values message key to template.
 */
class MapMessages(private val values: Map<String, String>) : Messages {
    override fun format(key: String, vararg params: Pair<String, String>): String =
        applyPlaceholders(values[key] ?: key, params)

    override fun raw(key: String): String = values[key] ?: key
}
