package org.helix.addons.chat

import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult
import org.helix.api.display.DisplayProfile

/**
 * A prefix rule: players with the permission get the prefix and color.
 *
 * Rules are evaluated in list order; the first matching rule wins, so the
 * most important rank belongs at the top.
 *
 * @property permission permission node identifying the rank.
 * @property prefix chat/tab prefix with `&` colors, for example `&cAdmin &f`.
 * @property color name color code, for example `&c`.
 */
@Serializable
data class PrefixRule(
    val permission: String,
    val prefix: String,
    val color: String = "&f",
)

/**
 * Persisted chat configuration.
 *
 * @property format chat line format with `{prefix}`, `{color}`, `{name}`,
 *   `{suffix}` and `{message}` placeholders.
 * @property rules prefix rules, first match wins.
 */
@Serializable
data class ChatConfig(
    val format: String = "{prefix}{color}{name} &8» &f{message}",
    val rules: List<PrefixRule> = emptyList(),
)

/**
 * Pretty chat addon.
 *
 * Publishes the chat format as a bridge value (rendered by the paper
 * bridge) and resolves per-player prefixes through the display resolver,
 * matching prefix rules against the permission system.
 */
class PrettyChatAddon : AddonBase() {
    private val json = Json { prettyPrint = true }
    private lateinit var config: ChatConfig

    /**
     * Publishes the format, registers the display resolver and actions.
     */
    override fun enable() {
        config = load()
        context.publishBridgeValue("chat.format", config.format)
        context.registerDisplayResolver { name ->
            config.rules.firstOrNull { rule -> context.hasPermission(name, rule.permission) }
                ?.let { rule -> DisplayProfile(prefix = rule.prefix, color = rule.color) }
        }
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
        action(
            "chat.prefix.add",
            "Adds a prefix rule: players with the permission get the prefix. First rule wins.",
            "chat.prefix.add <permission> <color> <prefix...>",
        ) { invocation ->
            val permission = invocation.arguments.getOrNull(0)
            val color = invocation.arguments.getOrNull(1)
            val prefix = invocation.arguments.drop(2).joinToString(" ")
            if (permission == null || color == null || prefix.isBlank()) {
                ActionResult.error("usage: chat.prefix.add <permission> <color> <prefix...>")
            } else {
                config = config.copy(
                    rules = config.rules.filter { it.permission != permission } +
                        PrefixRule(permission, "$prefix ", color),
                )
                save()
                ActionResult.ok("prefix rule for $permission added")
            }
        }
        action("chat.prefix.remove", "Removes a prefix rule.", "chat.prefix.remove <permission>") { invocation ->
            val permission = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: chat.prefix.remove <permission>")
            val remaining = config.rules.filter { it.permission != permission }
            if (remaining.size == config.rules.size) {
                ActionResult.error("no rule for $permission")
            } else {
                config = config.copy(rules = remaining)
                save()
                ActionResult.ok("prefix rule for $permission removed")
            }
        }
        action("chat.prefix.list", "Lists all prefix rules in match order.", "chat.prefix.list") {
            if (config.rules.isEmpty()) {
                ActionResult.ok("no prefix rules — chat uses the plain format")
            } else {
                ActionResult.ok(
                    *config.rules.map { "${it.permission} → '${it.prefix}' color='${it.color}'" }.toTypedArray(),
                )
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
        val file = context.dataDirectory.resolve("chat.json")
        return if (Files.exists(file)) json.decodeFromString(Files.readString(file)) else ChatConfig()
    }

    private fun save() {
        Files.writeString(context.dataDirectory.resolve("chat.json"), json.encodeToString(config))
    }
}
