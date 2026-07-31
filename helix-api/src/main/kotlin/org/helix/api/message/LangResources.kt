package org.helix.api.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads an addon's default message templates from bundled language files.
 *
 * The project convention is one flat JSON object per language under
 * `lang/` in the addon jar — `lang/en-EN.json` and `lang/de-DE.json` —
 * with message keys mapping to MiniMessage templates:
 *
 * ```json
 * { "usage": "<gray>/friend <white><add|remove></white>", "sent": "<gray>Request sent." }
 * ```
 *
 * The language id is the part before the dash (`de-DE` → `de`), matching
 * the language codes the platform already uses everywhere (`/helix
 * language`, [Messages.formatFor] resolution, the translations panel).
 */
object LangResources {
    private val json = Json { ignoreUnknownKeys = true }

    /** The locale files every addon ships, in resolution order. */
    val LOCALES: List<String> = listOf("en-EN", "de-DE")

    /**
     * Loads the language files bundled next to [owner]'s class.
     *
     * @param owner a class of the addon whose jar carries the `lang/`
     *  resources (typically the addon main class, via `javaClass`).
     * @return language code to (message key to template); languages whose
     *  file is missing are absent.
     * @throws IllegalStateException if no language file exists at all —
     *  an addon opting into file-based messages must ship at least one.
     */
    fun load(owner: Class<*>): Map<String, Map<String, String>> {
        val loaded = LOCALES.mapNotNull { locale ->
            val resource = owner.getResourceAsStream("/lang/$locale.json") ?: return@mapNotNull null
            val text = resource.bufferedReader().use { it.readText() }
            val entries = json.parseToJsonElement(text).jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content }
            locale.substringBefore('-') to entries
        }.toMap()
        check(loaded.isNotEmpty()) { "no lang/*.json resource found for ${owner.name}" }
        return loaded
    }
}
