package org.helix.addons.moderation

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Moderation addon.
 *
 * Permission-gated in-game commands for moderators: `/kick`, `/warn`,
 * `/warns`, `/announce` and `/tempban` (delegating to the ban addon when
 * installed). Enforcement runs entirely through generic platform actions.
 */
class ModerationAddon : AddonBase() {
    private lateinit var store: WarnStore
    private lateinit var msg: org.helix.api.message.Messages

    /**
     * Registers the moderation player commands.
     */
    override fun enable() {
        store = WarnStore(context.storage())
        msg = context.localizedMessages(
            mapOf(
                "en" to mapOf(
                    "kick.default" to "Kicked by a moderator.",
                    // Kick disconnect screen — MiniMessage, multi-line. Placeholders:
                    // {reason} {moderator} {staff} {player} {network} {date} {time}
                    "kick.screen" to (
                        "<red><bold>You were kicked</bold>\n \n" +
                            "<gray>Reason: <white>{reason}\n" +
                            "<gray>By: <white>{moderator}"
                        ),
                    "kick.confirm" to "&7Kicked &f{target}&7.",
                    "kick.notify" to "&c[Kick] &f{target} &7by {moderator}: {reason}",
                    "warn.player" to "&cYou have been warned: &f{reason}",
                    "warn.confirm" to "&7Warned &f{target}&7: {reason} ({total} total)",
                    "warn.notify" to "&e[Warn] &f{target} &7by {moderator}: {reason} ({total} total)",
                    "warns.none" to "&7{target} has no warnings.",
                    "warns.entry" to "&c{reason} &7— by {by}",
                    "announce.format" to "&c&l[Announcement] &r&f{text}",
                    "tempban.default" to "Banned by {moderator}",
                    "usage.kick" to "Usage: /kick \\<player> [reason...]",
                    "usage.warn" to "Usage: /warn \\<player> \\<reason...>",
                    "usage.warns" to "Usage: /warns \\<player>",
                    "usage.announce" to "Usage: /announce \\<text...>",
                    "usage.tempban" to "Usage: /tempban \\<player> \\<duration> [reason...]",
                ),
                "de" to mapOf(
                    "kick.default" to "Von einem Moderator gekickt.",
                    // Kick-Disconnect-Screen — MiniMessage, mehrzeilig. Platzhalter:
                    // {reason} {moderator} {staff} {player} {network} {date} {time}
                    "kick.screen" to (
                        "<red><bold>Du wurdest gekickt</bold>\n \n" +
                            "<gray>Grund: <white>{reason}\n" +
                            "<gray>Von: <white>{moderator}"
                        ),
                    "kick.confirm" to "&7Du hast &f{target}&7 gekickt.",
                    "kick.notify" to "&c[Kick] &f{target} &7von {moderator}: {reason}",
                    "warn.player" to "&cDu wurdest verwarnt: &f{reason}",
                    "warn.confirm" to "&7Du hast &f{target}&7 verwarnt: {reason} ({total} insgesamt)",
                    "warn.notify" to "&e[Warnung] &f{target} &7von {moderator}: {reason} ({total} insgesamt)",
                    "warns.none" to "&7{target} hat keine Verwarnungen.",
                    "warns.entry" to "&c{reason} &7— von {by}",
                    "announce.format" to "&c&l[Ankündigung] &r&f{text}",
                    "tempban.default" to "Gebannt von {moderator}",
                    "usage.kick" to "Verwendung: /kick \\<player> [reason...]",
                    "usage.warn" to "Verwendung: /warn \\<player> \\<reason...>",
                    "usage.warns" to "Verwendung: /warns \\<player>",
                    "usage.announce" to "Verwendung: /announce \\<text...>",
                    "usage.tempban" to "Verwendung: /tempban \\<player> \\<duration> [reason...]",
                ),
            ),
        )
        playerCommand(
            "kick",
            "Kicks a player from the network.",
            "kick <player> [reason...]",
            "helix.mod.kick",
        ) { executor, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage(executor, "usage.kick")
            val reason = args.drop(1).joinToString(" ").ifBlank { msg.formatFor(target, "kick.default") }
            val screen = msg.formatFor(
                target,
                "kick.screen",
                "reason" to reason,
                "moderator" to executor,
                "staff" to executor,
                "player" to target,
            )
            val result = invoke("player.kick", target, screen)
            if (result.success) {
                context.publishNotification("moderation", msg.format("kick.notify", "target" to target, "moderator" to executor, "reason" to reason))
                ActionResult.ok(msg.formatFor(executor, "kick.confirm", "target" to target))
            } else {
                result
            }
        }
        playerCommand(
            "warn",
            "Warns a player.",
            "warn <player> <reason...>",
            "helix.mod.warn",
        ) { executor, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage(executor, "usage.warn")
            val reason = args.drop(1).joinToString(" ").ifBlank { return@playerCommand usage(executor, "usage.warn") }
            store.warn(target, executor, reason)
            invoke("player.message", target, msg.formatFor(target, "warn.player", "reason" to reason))
            val total = store.warnsOf(target).size
            context.publishNotification("moderation", msg.format("warn.notify", "target" to target, "moderator" to executor, "reason" to reason, "total" to total.toString()))
            ActionResult.ok(msg.formatFor(executor, "warn.confirm", "target" to target, "reason" to reason, "total" to total.toString()))
        }
        playerCommand(
            "warns",
            "Shows a player's warn history.",
            "warns <player>",
            "helix.mod.warn",
        ) { executor, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage(executor, "usage.warns")
            val history = store.warnsOf(target)
            if (history.isEmpty()) {
                ActionResult.ok(msg.formatFor(executor, "warns.none", "target" to target))
            } else {
                ActionResult.ok(
                    *history.map { msg.formatFor(executor, "warns.entry", "reason" to it.reason, "by" to it.by) }.toTypedArray(),
                )
            }
        }
        playerCommand(
            "announce",
            "Broadcasts an announcement to the whole network.",
            "announce <text...>",
            "helix.mod.broadcast",
        ) { executor, args ->
            val text = args.joinToString(" ")
            if (text.isBlank()) {
                usage(executor, "usage.announce")
            } else {
                invoke("player.broadcast", msg.format("announce.format", "text" to text))
            }
        }
        playerCommand(
            "tempban",
            "Temporarily bans a player (requires the bans addon).",
            "tempban <player> <duration> [reason...]",
            "helix.mod.ban",
        ) { executor, args ->
            val target = args.getOrNull(0)
            val duration = args.getOrNull(1)
            if (target == null || duration == null) {
                usage(executor, "usage.tempban")
            } else {
                val reason = args.drop(2).joinToString(" ").ifBlank { msg.formatFor(target, "tempban.default", "moderator" to executor) }
                invoke("ban.set", target, duration, reason)
            }
        }
    }

    private fun playerCommand(
        name: String,
        description: String,
        usage: String,
        permission: String,
        handler: (String, List<String>) -> ActionResult,
    ) {
        context.registerAction(
            ActionDescriptor(
                name = name,
                description = description,
                usage = usage,
                playerCommand = true,
                permission = permission,
            ),
        ) { invocation ->
            val executor = invocation.arguments.firstOrNull()
                ?: return@registerAction ActionResult.error("missing executing player")
            handler(executor, invocation.arguments.drop(1))
        }
    }

    private fun invoke(action: String, vararg arguments: String): ActionResult =
        context.actions.invoke(ActionInvocation(action, arguments.toList(), ActionSource.ADDON))

    private fun usage(executor: String, key: String): ActionResult =
        ActionResult.error(msg.formatFor(executor, key))
}
