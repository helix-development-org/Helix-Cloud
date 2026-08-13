package org.helix.addons.translations.paper

import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.wire.ServiceNodeApi

/**
 * Node client for the translations editor, over the shared [ServiceNodeApi]
 * transport (Helix-Wire when up, HTTP otherwise). Reads the full translations
 * view and performs edits through the node's admin-gated
 * `helix.translations.*` actions; every call passes the acting player so the
 * node can re-check `helix.admin`.
 *
 * @property controlUrl the primary control url (`helix://` or `http://`).
 */
class NodeClient(val controlUrl: String, token: String) {
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        System.getenv("HELIX_SERVICE_ID").orEmpty(),
        token,
    ).also { it.start() }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches the full translations view for [player].
     *
     * @param player acting admin player name.
     * @return the view, or `null` when unreachable or not permitted.
     */
    fun view(player: String): TranslationsView? =
        firstLine("helix.translations.view", player)
            ?.let { runCatching { json.decodeFromString<TranslationsView>(it) }.getOrNull() }

    /**
     * Sets a translation value for a language.
     *
     * @param player acting admin player name.
     * @param key flat translation key.
     * @param language language code.
     * @param value template text (passed as a single argument, verbatim).
     * @return `true` on success.
     */
    fun set(player: String, key: String, language: String, value: String): Boolean =
        ok("helix.translations.set", player, key, language, value)

    /**
     * Resets a translation to its default for a language.
     *
     * @param player acting admin player name.
     * @param key flat translation key.
     * @param language language code.
     * @return `true` on success.
     */
    fun reset(player: String, key: String, language: String): Boolean =
        ok("helix.translations.reset", player, key, language)

    /**
     * Deletes a custom-created key across all languages.
     *
     * @param player acting admin player name.
     * @param key flat translation key.
     * @return `true` on success.
     */
    fun deleteKey(player: String, key: String): Boolean =
        ok("helix.translations.deleteKey", player, key)

    /**
     * Adds a network language.
     *
     * @param player acting admin player name.
     * @param language language code.
     * @return `true` on success.
     */
    fun addLanguage(player: String, language: String): Boolean =
        ok("helix.translations.language.add", player, language)

    /**
     * Removes a network language.
     *
     * @param player acting admin player name.
     * @param language language code.
     * @return `true` on success.
     */
    fun removeLanguage(player: String, language: String): Boolean =
        ok("helix.translations.language.remove", player, language)

    /**
     * Sets the network default language.
     *
     * @param player acting admin player name.
     * @param language language code.
     * @return `true` on success.
     */
    fun setDefaultLanguage(player: String, language: String): Boolean =
        ok("helix.translations.language.default", player, language)

    private fun firstLine(name: String, vararg args: String): String? {
        val result = api.action(ActionInvocation(name, args.toList())) ?: return null
        return if (result.success) result.lines.firstOrNull().orEmpty() else null
    }

    private fun ok(name: String, vararg args: String): Boolean =
        api.action(ActionInvocation(name, args.toList()))?.success ?: false

    /** Closes the underlying transport. */
    fun close(): Unit = api.close()

    companion object {
        /**
         * Builds and starts a client from the wrapper environment.
         *
         * @return the started client, or `null` when the Helix environment is absent.
         */
        fun fromEnvironment(): NodeClient? {
            val url = System.getenv("HELIX_CONTROL_URL") ?: return null
            val token = System.getenv("HELIX_CONTROL_TOKEN") ?: return null
            return NodeClient(url, token)
        }
    }
}
