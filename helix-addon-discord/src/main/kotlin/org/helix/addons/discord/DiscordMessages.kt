package org.helix.addons.discord

import org.helix.api.message.Messages
import org.helix.api.message.applyPlaceholders

/**
 * Translation helper for Discord-facing text.
 *
 * Discord output must stay free of the Minecraft chat prefix and of
 * MiniMessage tags, so this resolves raw templates ([Messages.rawIn]) and
 * substitutes placeholders itself: ephemeral interaction replies follow
 * the interacting user's Discord client locale, shared messages (panels,
 * status board, audit log) the network's default language.
 *
 * @property messages the addon's live message bundle.
 */
class DiscordMessages(private val messages: Messages) {
    /**
     * Formats a shared message in the network's default language.
     *
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted text.
     */
    fun t(key: String, vararg params: Pair<String, String>): String =
        applyPlaceholders(messages.raw(key), params)

    /**
     * Formats an interaction reply in the user's Discord locale.
     *
     * @param locale Discord client locale, for example `de` or `en-US`;
     *   `null` falls back to the network default.
     * @param key message key.
     * @param params placeholder name to value pairs.
     * @return the formatted text.
     */
    fun tl(locale: String?, key: String, vararg params: Pair<String, String>): String {
        val language = locale?.substringBefore('-')?.lowercase()
        val template = if (language == null) messages.raw(key) else messages.rawIn(language, key)
        return applyPlaceholders(template, params)
    }

    companion object {
        private val COLOR_CODES = Regex("&[0-9a-fk-orA-FK-OR]|<[^<>]{1,32}>")

        /**
         * Strips `&` color codes and MiniMessage tags out of Minecraft
         * text before it is shown on Discord.
         *
         * @param text minecraft-formatted text.
         * @return plain text.
         */
        fun stripColors(text: String): String = text.replace(COLOR_CODES, "")

        /**
         * Truncates text to a Discord component limit, appending an
         * ellipsis when cut.
         *
         * @param text the text.
         * @param limit maximum length.
         * @return the possibly shortened text.
         */
        fun truncate(text: String, limit: Int): String =
            if (text.length <= limit) text else text.take(limit - 1) + "…"
    }
}
