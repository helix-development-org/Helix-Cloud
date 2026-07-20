package org.helix.api.message

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
