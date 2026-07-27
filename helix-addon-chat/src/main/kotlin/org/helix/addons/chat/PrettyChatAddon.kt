package org.helix.addons.chat

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Pretty chat addon.
 *
 * Publishes the chat format as a bridge value (rendered by the paper
 * bridge). The `{prefix}`/`{color}` placeholders are filled by the
 * display pipeline — group prefixes live in the permissions addon
 * (`perm.group.prefix`), the nick in the nick addon, the clan tag in the
 * clan addon; this addon only owns the line format.
 */
class PrettyChatAddon : AddonBase() {
    // Stored configs from before 0.56 carry a legacy "rules" list — ignore it on decode.
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private lateinit var config: ChatConfig

    /**
     * Publishes the format and registers the format actions.
     */
    override fun enable() {
        config = load()
        context.publishBridgeValue("chat.format", config.format)
        action(
            "chat.format",
            "Sets the chat format. Placeholders: {prefix} {color} {name} {suffix} {message}.",
            "chat.format <format...>",
        ) { invocation ->
            val format = invocation.arguments.joinToString(" ")
            if (!format.contains("{message}")) {
                ActionResult.error("format must contain {message}")
            } else {
                config = config.copy(format = format)
                save()
                context.publishBridgeValue("chat.format", format)
                ActionResult.ok("chat format updated")
            }
        }
        action("chat.export", "Exports the chat configuration as JSON (dashboard).", "chat.export") {
            ActionResult.ok(json.encodeToString(config))
        }
        panel(
            "chat",
            "Chat",
            "/panel.html",
            "<path d=\"M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z\"/>",
        )
    }

    private fun load(): ChatConfig {
        val stored = context.storage().read("chat")?.let { json.decodeFromString<ChatConfig>(it) } ?: ChatConfig()
        // Migration: configs persisted before the display-name split lack {suffix} (the clan tag
        // renders there); without it the suffix component would silently never show in chat.
        if ("{suffix}" in stored.format || "{name}" !in stored.format) return stored
        return stored.copy(format = stored.format.replace("{name}", "{name}{suffix}")).also { migrated ->
            context.storage().write("chat", json.encodeToString(migrated))
        }
    }

    private fun save() {
        context.storage().write("chat", json.encodeToString(config))
    }
}
