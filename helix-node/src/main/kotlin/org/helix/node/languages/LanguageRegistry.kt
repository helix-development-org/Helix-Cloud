package org.helix.node.languages

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * The network's languages and every player's language preference, persisted
 * through the node's document storage.
 *
 * Ships with English (`en`, the default) and German (`de`). Operators add
 * further languages on the dashboard; players pick theirs with
 * `/helix language`. A player without an explicit choice gets the language
 * matching their Minecraft client locale on first join, falling back to the
 * network default.
 *
 * @property storage node-scoped document store (owner `translations`).
 */
class LanguageRegistry(
    private val storage: AddonStorage,
) {
    private val json = Json { prettyPrint = true }
    private var config: LanguageConfig
    private val players = linkedMapOf<String, String>()
    private var onChange: () -> Unit = {}

    init {
        config = storage.read(CONFIG_DOCUMENT)
            ?.let { raw -> runCatching { json.decodeFromString<LanguageConfig>(raw) }.getOrNull() }
            ?: LanguageConfig()
        storage.read(PLAYERS_DOCUMENT)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }
                .getOrDefault(emptyMap())
                .forEach { (name, language) -> players[name] = language }
        }
    }

    /**
     * Registers the listener invoked after any language or preference change.
     *
     * @param listener change callback.
     */
    fun onChange(listener: () -> Unit) {
        onChange = listener
    }

    /**
     * Every configured language code, default language first.
     *
     * @return language codes.
     */
    @Synchronized
    fun languages(): List<String> = config.languages

    /**
     * The network-wide fallback language.
     *
     * @return the default language code.
     */
    @Synchronized
    fun defaultLanguage(): String = config.default

    /**
     * Adds a language.
     *
     * @param language new language code, for example `fr`.
     * @return `true` if it was added, `false` if it already existed or the
     *   code is not a valid lowercase ISO-style tag.
     */
    @Synchronized
    fun addLanguage(language: String): Boolean {
        val code = language.trim().lowercase()
        if (!code.matches(LANGUAGE_PATTERN) || code in config.languages) {
            return false
        }
        config = config.copy(languages = config.languages + code)
        persistConfig()
        onChange()
        return true
    }

    /**
     * Removes a language and every player preference pointing at it.
     *
     * @param language language code.
     * @return `true` if removed; the default language cannot be removed.
     */
    @Synchronized
    fun removeLanguage(language: String): Boolean {
        val code = language.trim().lowercase()
        if (code == config.default || code !in config.languages) {
            return false
        }
        config = config.copy(languages = config.languages - code)
        persistConfig()
        val orphaned = players.filterValues { it == code }.keys
        if (orphaned.isNotEmpty()) {
            orphaned.forEach(players::remove)
            persistPlayers()
        }
        onChange()
        return true
    }

    /**
     * Changes the network-wide fallback language.
     *
     * @param language language code; must already be configured.
     * @return `true` if changed.
     */
    @Synchronized
    fun setDefaultLanguage(language: String): Boolean {
        val code = language.trim().lowercase()
        if (code !in config.languages) {
            return false
        }
        config = config.copy(default = code)
        persistConfig()
        onChange()
        return true
    }

    /**
     * A player's effective language.
     *
     * @param player player name.
     * @return the stored preference, or the default language.
     */
    @Synchronized
    fun languageOf(player: String): String = players[player.lowercase()] ?: config.default

    /**
     * All stored player preferences.
     *
     * @return lowercase player name to language code.
     */
    @Synchronized
    fun playerLanguages(): Map<String, String> = players.toMap()

    /**
     * Stores a player's explicit language choice.
     *
     * @param player player name.
     * @param language language code; must be configured.
     * @return `true` if stored.
     */
    @Synchronized
    fun setPlayerLanguage(player: String, language: String): Boolean {
        val code = language.trim().lowercase()
        if (code !in config.languages) {
            return false
        }
        players[player.lowercase()] = code
        persistPlayers()
        onChange()
        return true
    }

    /**
     * Applies a Minecraft client locale as first-join default.
     *
     * Only takes effect when the player has no stored preference yet and a
     * configured language matches the locale's language part (for example
     * `de_de` matches `de`).
     *
     * @param player player name.
     * @param locale client locale, for example `de_de` or `en_us`.
     * @return `true` if a preference was stored.
     */
    @Synchronized
    fun applyClientLocale(player: String, locale: String): Boolean {
        val name = player.lowercase()
        if (name in players) {
            return false
        }
        val code = locale.trim().lowercase().substringBefore('_')
        if (code !in config.languages) {
            return false
        }
        players[name] = code
        persistPlayers()
        onChange()
        return true
    }

    private fun persistConfig() {
        storage.write(CONFIG_DOCUMENT, json.encodeToString(config))
    }

    private fun persistPlayers() {
        storage.write(PLAYERS_DOCUMENT, json.encodeToString(players.toMap()))
    }

    /**
     * Persisted language configuration.
     *
     * @property default network-wide fallback language code.
     * @property languages every configured language code.
     */
    @Serializable
    private data class LanguageConfig(
        val default: String = "en",
        val languages: List<String> = listOf("en", "de"),
    )

    private companion object {
        /** Document key of the language configuration. */
        const val CONFIG_DOCUMENT = "config"

        /** Document key of the player preference map. */
        const val PLAYERS_DOCUMENT = "players"

        /** Valid language codes: `en`, `de`, `pt-br`, … */
        val LANGUAGE_PATTERN = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?")
    }
}
