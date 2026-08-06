package org.helix.api.i18n

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json

/**
 * Node-backed translations for a Paper/Velocity addon component: a local
 * copy of the node's translation state, refreshed by the owning plugin's
 * scheduler, resolved per player and per language.
 *
 * This is THE way a Paper-side addon component localizes its player-facing
 * text — the templates live in the owning node addon's `lang` resource
 * files (panel-editable like every other message), never hardcoded in
 * plugin code. Keys are resolved below the owner's namespace
 * (`helix.translations.<owner>.<key>`).
 *
 * @property controlUrl base control API url, for example `http://127.0.0.1:8080`.
 * @property token per-service bearer token from `HELIX_CONTROL_TOKEN`.
 * @property owner owning addon id, for example `helix.profile`.
 */
class NodeTranslations(
    private val controlUrl: String,
    private val token: String,
    private val owner: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Volatile
    private var snapshot = TranslationsSnapshot()

    /**
     * Fetches the latest snapshot from the node; failures keep the old one.
     * Call periodically from the plugin scheduler (async).
     */
    fun sync() {
        val request = HttpRequest.newBuilder(URI.create(controlUrl.trimEnd('/') + "/api/v1/internal/translations"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            snapshot = json.decodeFromString<TranslationsSnapshot>(response.body())
        }
    }

    /**
     * Resolves one of this owner's keys in the player's language, with the
     * network prefix prepended (the same rule chat messages follow
     * everywhere; use [screen] for prefix-free text).
     *
     * @param player receiving player name.
     * @param clientLanguage the player's Minecraft client language code,
     *  used when they picked no explicit preference; `null` skips it.
     * @param key key below `helix.translations.<owner>.`.
     * @param params placeholder name to value pairs.
     * @return the resolved text, or the key itself while unsynced/unknown.
     */
    fun text(player: String, clientLanguage: String?, key: String, vararg params: Pair<String, String>): String {
        val resolved = resolve(player, clientLanguage, key) ?: return key
        return substitute(prefixOf(player, clientLanguage) + resolved, params)
    }

    /**
     * Resolves one of this owner's keys WITHOUT the network prefix — for
     * screens, GUI titles and other non-chat text.
     *
     * @param player receiving player name.
     * @param clientLanguage the player's client language code, or `null`.
     * @param key key below `helix.translations.<owner>.`.
     * @param params placeholder name to value pairs.
     * @return the resolved text, or the key itself while unsynced/unknown.
     */
    fun screen(player: String, clientLanguage: String?, key: String, vararg params: Pair<String, String>): String {
        val resolved = resolve(player, clientLanguage, key) ?: return key
        return substitute(resolved, params)
    }

    private fun prefixOf(player: String, clientLanguage: String?): String {
        val prefix = resolveFlat(player, clientLanguage, "helix.translations.network.prefix") ?: return ""
        return if (prefix.isBlank()) "" else "$prefix "
    }

    private fun resolve(player: String, clientLanguage: String?, key: String): String? =
        resolveFlat(player, clientLanguage, "helix.translations.$owner.$key")

    private fun resolveFlat(player: String, clientLanguage: String?, flatKey: String): String? {
        val current = snapshot
        val language = current.playerLanguages[player.lowercase()]
            ?: clientLanguage?.takeIf { it in current.languages }
            ?: current.defaultLanguage
        return current.values[language]?.get(flatKey)
            ?: current.values[current.defaultLanguage]?.get(flatKey)
    }

    private fun substitute(template: String, params: Array<out Pair<String, String>>): String {
        var result = template
        params.forEach { (name, value) -> result = result.replace("{$name}", value) }
        return result
    }

    /**
     * Closes the underlying HTTP client — call from the owning plugin's
     * onDisable so a Bukkit `/reload` does not leak the client (and, through
     * it, the old plugin classloader).
     */
    fun close() {
        http.close()
    }
}
