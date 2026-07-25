package org.helix.api.i18n

import kotlinx.serialization.Serializable

/**
 * Complete translation state a bridge syncs from the node.
 *
 * Bridges resolve a key for a player by looking up the player's language in
 * [playerLanguages] (falling back to the player's client locale, then
 * [defaultLanguage]) and reading `values[language][key]`, falling back to
 * `values[defaultLanguage][key]`.
 *
 * @property defaultLanguage network-wide fallback language code.
 * @property languages every language configured on the node.
 * @property playerLanguages language preference per online player name
 *  (lowercase), only players with an explicit or first-join preference.
 * @property values effective translations: language code to (flat key to
 *  template), custom panel edits already overlaid on the defaults.
 */
@Serializable
data class TranslationsSnapshot(
    val defaultLanguage: String = "en",
    val languages: List<String> = emptyList(),
    val playerLanguages: Map<String, String> = emptyMap(),
    val values: Map<String, Map<String, String>> = emptyMap(),
)
