package org.helix.node.actions

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.node.languages.LanguageRegistry
import org.helix.node.messages.MessageRegistry

/**
 * Bridge-invocable actions that let an authorized in-game editor (the
 * `helix-addon-translations` Paper GUI, `/translationsmenu`) read and modify
 * the network's translations, mirroring the dashboard's
 * `/api/v1/translations` routes.
 *
 * Every action takes the acting player as its first argument and is
 * re-checked node-side against `helix.admin`: the Paper command is already
 * permission-gated, and this is defence in depth so that holding a
 * per-service token alone can never rewrite network messages. Writes are
 * propagated to the bridges through [onMessagesChanged], exactly like the
 * dashboard routes do.
 *
 * The translation value of [set] is passed as a single, final argument so
 * that spacing and MiniMessage tags survive verbatim (no join/split).
 *
 * @property messages the flat translation registry.
 * @property languages network languages and per-player preferences.
 * @property adminCheck whether a player holds `helix.admin`.
 * @property onMessagesChanged invoked with the owner id after a write, to
 *  propagate the change to the bridges.
 */
class TranslationActions(
    private val messages: MessageRegistry,
    private val languages: LanguageRegistry,
    private val adminCheck: (player: String) -> Boolean,
    private val onMessagesChanged: (owner: String) -> Unit = {},
) {
    private val json = Json

    /**
     * Registers every translation-editor action on [registry].
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        register(
            registry,
            "helix.translations.view",
            "Full translations view (languages, defaults and custom values) for the in-game editor.",
            "helix.translations.view <player>",
        ) { view(it) }
        register(
            registry,
            "helix.translations.set",
            "Sets a translation value for a language.",
            "helix.translations.set <player> <key> <language> <value>",
        ) { set(it) }
        register(
            registry,
            "helix.translations.reset",
            "Resets a translation to its default for a language.",
            "helix.translations.reset <player> <key> <language>",
        ) { reset(it) }
        register(
            registry,
            "helix.translations.deleteKey",
            "Deletes a custom-created key across all languages.",
            "helix.translations.deleteKey <player> <key>",
        ) { deleteKey(it) }
        register(
            registry,
            "helix.translations.language.add",
            "Adds a network language.",
            "helix.translations.language.add <player> <language>",
        ) { addLanguage(it) }
        register(
            registry,
            "helix.translations.language.remove",
            "Removes a network language.",
            "helix.translations.language.remove <player> <language>",
        ) { removeLanguage(it) }
        register(
            registry,
            "helix.translations.language.default",
            "Sets the network default language.",
            "helix.translations.language.default <player> <language>",
        ) { setDefaultLanguage(it) }
    }

    private fun view(invocation: ActionInvocation): ActionResult = authorized(invocation) {
        val entries = messages.entries().map { entry ->
            EditorEntry(
                key = entry.key,
                owner = messages.ownerOf(entry.key).orEmpty(),
                values = entry.values,
                defaults = entry.defaults,
            )
        }
        ActionResult.ok(
            json.encodeToString(
                EditorView(
                    languages = languages.languages(),
                    defaultLanguage = languages.defaultLanguage(),
                    entries = entries,
                ),
            ),
        )
    }

    private fun set(invocation: ActionInvocation): ActionResult = authorized(invocation) {
        val key = invocation.arguments.getOrNull(1)
            ?: return@authorized ActionResult.error("usage: helix.translations.set <player> <key> <language> <value>")
        val language = invocation.arguments.getOrNull(2)
            ?: return@authorized ActionResult.error("usage: helix.translations.set <player> <key> <language> <value>")
        val value = invocation.arguments.getOrNull(3) ?: ""
        if (messages.set(key, language, value)) {
            messages.ownerOf(key)?.let(onMessagesChanged)
            ActionResult.ok("updated $key ($language)")
        } else {
            ActionResult.error("unknown translation $key")
        }
    }

    private fun reset(invocation: ActionInvocation): ActionResult = authorized(invocation) {
        val key = invocation.arguments.getOrNull(1)
            ?: return@authorized ActionResult.error("usage: helix.translations.reset <player> <key> <language>")
        val language = invocation.arguments.getOrNull(2)
            ?: return@authorized ActionResult.error("usage: helix.translations.reset <player> <key> <language>")
        if (messages.reset(key, language)) {
            messages.ownerOf(key)?.let(onMessagesChanged)
            ActionResult.ok("reset $key ($language)")
        } else {
            ActionResult.error("no custom value for $key ($language)")
        }
    }

    private fun deleteKey(invocation: ActionInvocation): ActionResult = authorized(invocation) {
        val key = invocation.arguments.getOrNull(1)
            ?: return@authorized ActionResult.error("usage: helix.translations.deleteKey <player> <key>")
        if (messages.deleteKey(key)) {
            messages.ownerOf(key)?.let(onMessagesChanged)
            ActionResult.ok("deleted $key")
        } else {
            ActionResult.error("unknown or default-backed key: $key")
        }
    }

    private fun addLanguage(invocation: ActionInvocation): ActionResult = authorized(invocation) {
        val language = invocation.arguments.getOrNull(1)
            ?: return@authorized ActionResult.error("usage: helix.translations.language.add <player> <language>")
        if (languages.addLanguage(language)) {
            ActionResult.ok("added $language")
        } else {
            ActionResult.error("invalid language: $language")
        }
    }

    private fun removeLanguage(invocation: ActionInvocation): ActionResult = authorized(invocation) {
        val language = invocation.arguments.getOrNull(1)
            ?: return@authorized ActionResult.error("usage: helix.translations.language.remove <player> <language>")
        if (languages.removeLanguage(language)) {
            ActionResult.ok("removed $language")
        } else {
            ActionResult.error("cannot remove language: $language")
        }
    }

    private fun setDefaultLanguage(invocation: ActionInvocation): ActionResult = authorized(invocation) {
        val language = invocation.arguments.getOrNull(1)
            ?: return@authorized ActionResult.error("usage: helix.translations.language.default <player> <language>")
        if (languages.setDefaultLanguage(language)) {
            ActionResult.ok("default set to $language")
        } else {
            ActionResult.error("invalid language: $language")
        }
    }

    /**
     * Resolves and admin-checks the acting player, then runs [block]; returns
     * an error result when the player is missing or lacks `helix.admin`.
     */
    private inline fun authorized(invocation: ActionInvocation, block: (player: String) -> ActionResult): ActionResult {
        val player = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        if (!adminCheck(player)) {
            return ActionResult.error("not permitted")
        }
        return block(player)
    }

    private fun register(
        registry: ActionRegistry,
        name: String,
        description: String,
        usage: String,
        handler: (ActionInvocation) -> ActionResult,
    ) {
        registry.register(
            ActionDescriptor(
                name = name,
                description = description,
                usage = usage,
                playerCommand = false,
                permission = null,
                bridgeInvocable = true,
            ),
            handler,
        )
    }
}

/**
 * One translation key for the in-game editor: like the dashboard's
 * `TranslationEntry` but also carrying the resolved [owner], so the Paper GUI
 * can group keys by addon (owner ids may contain dots and are not derivable
 * from the flat key alone).
 *
 * @property key flat translation key.
 * @property owner owning addon/subsystem id.
 * @property values custom values per language.
 * @property defaults declared defaults per language.
 */
@Serializable
private data class EditorEntry(
    val key: String,
    val owner: String,
    val values: Map<String, String>,
    val defaults: Map<String, String>,
)

/**
 * The in-game editor's full translations view.
 *
 * @property languages every configured language code.
 * @property defaultLanguage network-wide fallback language.
 * @property entries every translation key with owner, values and defaults.
 */
@Serializable
private data class EditorView(
    val languages: List<String>,
    val defaultLanguage: String,
    val entries: List<EditorEntry>,
)
