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
     * Resolves against the network's default language; prefer
     * [formatFor] whenever the receiving player is known.
     *
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted message, or the key itself if unknown.
     */
    fun format(key: String, vararg params: Pair<String, String>): String

    /**
     * Formats a message in the receiving player's language.
     *
     * Falls back to the network's default language when the player has no
     * preference or the key is not translated. Implementations without
     * language support resolve like [format].
     *
     * @param player receiving player name.
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted message, or the key itself if unknown.
     */
    fun formatFor(player: String, key: String, vararg params: Pair<String, String>): String =
        format(key, *params)

    /**
     * Returns the raw template in the receiving player's language, without
     * substitution.
     *
     * @param player receiving player name.
     * @param key message key.
     * @return the template, or the key itself if unknown.
     */
    fun rawFor(player: String, key: String): String = raw(key)

    /**
     * Formats a message from a parameter map — the Java-friendly overload.
     *
     * @param key message key.
     * @param params placeholder name to value.
     * @return the formatted message, or the key itself if unknown.
     */
    fun format(key: String, params: Map<String, String>): String =
        applyPlaceholders(raw(key), params.entries.map { it.key to it.value }.toTypedArray())

    /**
     * Returns the raw template without substitution.
     *
     * @param key message key.
     * @return the template, or the key itself if unknown.
     */
    fun raw(key: String): String
}
