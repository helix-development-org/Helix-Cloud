package org.helix.addons.moderation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val json = Json { prettyPrint = true }
    private lateinit var config: ModerationConfig
    private lateinit var store: WarnStore
    private lateinit var mutes: MuteStore
    private lateinit var msg: org.helix.api.message.Messages

    /**
     * Registers the moderation player commands.
     */
    override fun enable() {
        config = loadConfig()
        store = WarnStore(
            context.storage(),
            resolveUuid = context::resolvePlayerUuid,
            expiryMillis = { config.warnExpiryDays.toLong() * 86_400_000 },
        )
        mutes = MuteStore(context.storage())
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
                    "mute.player" to "&cYou have been muted: &f{reason} &7({expiry})",
                    "mute.confirm" to "&7Muted &f{target}&7: {reason} ({expiry})",
                    "mute.notify" to "&e[Mute] &f{target} &7by {moderator}: {reason} ({expiry})",
                    "mute.default" to "Muted by a moderator.",
                    "unmute.confirm" to "&7Unmuted &f{target}&7.",
                    "unmute.notify" to "&a[Unmute] &f{target} &7by {moderator}.",
                    "unmute.none" to "&c{target} is not muted.",
                    "mutes.none" to "&7No active mutes.",
                    "mutes.entry" to "&e{player} &7— {reason} ({expiry}) &7— by {by}",
                    "blocklist.added" to "&7Added &f{word} &7to the chat blocklist.",
                    "blocklist.removed" to "&7Removed &f{word} &7from the chat blocklist.",
                    "blocklist.notfound" to "&c{word} is not on the blocklist.",
                    "blocklist.list" to "&eBlocked words: &f{words}",
                    "blocklist.empty" to "&7The chat blocklist is empty.",
                    "usage.mute" to "Usage: /mute \\<player> [duration] [reason...]",
                    "usage.unmute" to "Usage: /unmute \\<player>",
                    "usage.blocklist" to "Usage: /blocklist \\<add|remove|list> [word]",
                    "modlookup.header" to "&e&lModeration status for &f{target}",
                    "modlookup.ban" to "&7Ban: &f{value}",
                    "modlookup.mute" to "&7Mute: &f{value}",
                    "modlookup.warns" to "&7Active warns: &f{value}",
                    "modlookup.incidents" to "&7Guard incidents (recent): &f{value}",
                    "modlookup.na" to "n/a (addon not installed)",
                    "modlookup.none" to "none",
                    "usage.modlookup" to "Usage: /modlookup \\<player>",
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
                    "mute.player" to "&cDu wurdest stummgeschaltet: &f{reason} &7({expiry})",
                    "mute.confirm" to "&7Du hast &f{target}&7 stummgeschaltet: {reason} ({expiry})",
                    "mute.notify" to "&e[Mute] &f{target} &7von {moderator}: {reason} ({expiry})",
                    "mute.default" to "Von einem Moderator stummgeschaltet.",
                    "unmute.confirm" to "&7Du hast &f{target}&7 wieder freigeschaltet.",
                    "unmute.notify" to "&a[Unmute] &f{target} &7von {moderator}.",
                    "unmute.none" to "&c{target} ist nicht stummgeschaltet.",
                    "mutes.none" to "&7Keine aktiven Stummschaltungen.",
                    "mutes.entry" to "&e{player} &7— {reason} ({expiry}) &7— von {by}",
                    "blocklist.added" to "&7&f{word} &7zur Chat-Blockliste hinzugefügt.",
                    "blocklist.removed" to "&7&f{word} &7von der Chat-Blockliste entfernt.",
                    "blocklist.notfound" to "&c{word} steht nicht auf der Blockliste.",
                    "blocklist.list" to "&eBlockierte Wörter: &f{words}",
                    "blocklist.empty" to "&7Die Chat-Blockliste ist leer.",
                    "usage.mute" to "Verwendung: /mute \\<player> [duration] [reason...]",
                    "usage.unmute" to "Verwendung: /unmute \\<player>",
                    "usage.blocklist" to "Verwendung: /blocklist \\<add|remove|list> [word]",
                    "modlookup.header" to "&e&lModerationsstatus für &f{target}",
                    "modlookup.ban" to "&7Bann: &f{value}",
                    "modlookup.mute" to "&7Stummschaltung: &f{value}",
                    "modlookup.warns" to "&7Aktive Verwarnungen: &f{value}",
                    "modlookup.incidents" to "&7Guard-Vorfälle (kürzlich): &f{value}",
                    "modlookup.na" to "n/a (Addon nicht installiert)",
                    "modlookup.none" to "keine",
                    "usage.modlookup" to "Verwendung: /modlookup \\<player>",
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
                invoke("ban.set", target, executor, duration, reason)
            }
        }
        playerCommand(
            "mute",
            "Mutes a player, optionally temporary (30m, 12h, 7d).",
            "mute <player> [duration] [reason...]",
            "helix.mod.mute",
        ) { executor, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage(executor, "usage.mute")
            val durationToken = args.getOrNull(1)?.takeIf(MuteDuration::isDurationToken)
            val durationMs = durationToken?.let(MuteDuration::parseMillis)
            val reason = args.drop(if (durationToken != null) 2 else 1).joinToString(" ")
                .ifBlank { msg.formatFor(target, "mute.default") }
            val entry = mutes.set(target, reason, durationMs, executor)
            publishMutes()
            val expiry = expiryText(entry.expiresAtEpochMs)
            invoke("player.message", target, msg.formatFor(target, "mute.player", "reason" to reason, "expiry" to expiry))
            context.publishNotification(
                "moderation",
                msg.format("mute.notify", "target" to target, "moderator" to executor, "reason" to reason, "expiry" to expiry),
            )
            ActionResult.ok(msg.formatFor(executor, "mute.confirm", "target" to target, "reason" to reason, "expiry" to expiry))
        }
        playerCommand(
            "unmute",
            "Lifts a player's mute.",
            "unmute <player>",
            "helix.mod.mute",
        ) { executor, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage(executor, "usage.unmute")
            if (!mutes.unmute(target)) {
                return@playerCommand ActionResult.error(msg.formatFor(executor, "unmute.none", "target" to target))
            }
            publishMutes()
            context.publishNotification("moderation", msg.format("unmute.notify", "target" to target, "moderator" to executor))
            ActionResult.ok(msg.formatFor(executor, "unmute.confirm", "target" to target))
        }
        playerCommand(
            "mutes",
            "Lists all active mutes.",
            "mutes",
            "helix.mod.mute",
        ) { executor, _ ->
            val active = mutes.all()
            if (active.isEmpty()) {
                ActionResult.ok(msg.formatFor(executor, "mutes.none"))
            } else {
                ActionResult.ok(
                    *active.map {
                        msg.formatFor(
                            executor,
                            "mutes.entry",
                            "player" to it.player,
                            "reason" to it.reason,
                            "expiry" to expiryText(it.expiresAtEpochMs),
                            "by" to it.issuedBy.ifBlank { "unknown" },
                        )
                    }.toTypedArray(),
                )
            }
        }
        playerCommand(
            "blocklist",
            "Manages the chat word filter.",
            "blocklist <add|remove|list> [word]",
            "helix.mod.blocklist",
        ) { executor, args ->
            val sub = args.firstOrNull()?.lowercase()
            val word = args.getOrNull(1)?.lowercase()
            when (sub) {
                "add" -> {
                    if (word == null) return@playerCommand usage(executor, "usage.blocklist")
                    config = config.copy(blockedWords = (config.blockedWords + word).distinct())
                    saveConfig()
                    publishBlocklist()
                    ActionResult.ok(msg.formatFor(executor, "blocklist.added", "word" to word))
                }
                "remove" -> {
                    if (word == null) return@playerCommand usage(executor, "usage.blocklist")
                    if (word !in config.blockedWords) {
                        return@playerCommand ActionResult.error(msg.formatFor(executor, "blocklist.notfound", "word" to word))
                    }
                    config = config.copy(blockedWords = config.blockedWords - word)
                    saveConfig()
                    publishBlocklist()
                    ActionResult.ok(msg.formatFor(executor, "blocklist.removed", "word" to word))
                }
                "list" -> if (config.blockedWords.isEmpty()) {
                    ActionResult.ok(msg.formatFor(executor, "blocklist.empty"))
                } else {
                    ActionResult.ok(msg.formatFor(executor, "blocklist.list", "words" to config.blockedWords.sorted().joinToString()))
                }
                else -> usage(executor, "usage.blocklist")
            }
        }
        playerCommand(
            "modlookup",
            "Shows a player's ban/mute/warn/incident status at a glance.",
            "modlookup <player>",
            "helix.mod.lookup",
        ) { executor, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage(executor, "usage.modlookup")
            ActionResult.ok(
                msg.formatFor(executor, "modlookup.header", "target" to target),
                msg.formatFor(executor, "modlookup.ban", "value" to lookupBan(executor, target)),
                msg.formatFor(executor, "modlookup.mute", "value" to lookupMute(executor, target)),
                msg.formatFor(executor, "modlookup.warns", "value" to store.warnsOf(target).size.toString()),
                msg.formatFor(executor, "modlookup.incidents", "value" to lookupIncidentCount(executor, target)),
            )
        }
        context.registerPlayerDataProvider(
            /** Exports the player's warn history; clears it on delete. */
            object : org.helix.api.addon.PlayerDataProvider {
                override fun export(player: String): String? =
                    store.warnsOf(player).takeIf { it.isNotEmpty() }
                        ?.let { kotlinx.serialization.json.Json.encodeToString(it) }

                override fun delete(player: String): Boolean = store.clear(player)
            },
        )
        publishMutes()
        publishBlocklist()
        publishMuteMessages()
        action(
            "warn.expiry.get",
            "Shows how many days a warning stays active before it drops out of the warn count.",
            "warn.expiry.get",
        ) { ActionResult.ok("${config.warnExpiryDays} day(s)") }
        action(
            "warn.expiry.set",
            "Sets how many days a warning stays active before it drops out of the warn count.",
            "warn.expiry.set <days>",
        ) { invocation ->
            val days = invocation.arguments.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
                ?: return@action ActionResult.error("usage: warn.expiry.set <days>")
            config = config.copy(warnExpiryDays = days)
            saveConfig()
            ActionResult.ok("warns now expire after $days day(s)")
        }
    }

    private fun action(name: String, description: String, usage: String, handler: (ActionInvocation) -> ActionResult) {
        context.registerAction(ActionDescriptor(name, description, usage), handler)
    }

    private fun loadConfig(): ModerationConfig =
        context.storage().read(CONFIG_DOCUMENT)?.let { json.decodeFromString(it) } ?: ModerationConfig()

    private fun saveConfig() {
        context.storage().write(CONFIG_DOCUMENT, json.encodeToString(config))
    }

    private fun expiryText(expiresAtEpochMs: Long?): String =
        expiresAtEpochMs?.let { "expires in ${MuteDuration.format(it - System.currentTimeMillis())}" } ?: "permanent"

    /**
     * Ban status for `/modlookup`, delegating to the bans addon's `ban.check`
     * action (parsing its human-readable "not banned" line, since that
     * action reports "not banned" as a successful, not an error, result) —
     * `modlookup.na` when the bans addon is not installed at all.
     */
    private fun lookupBan(executor: String, target: String): String {
        val result = context.actions.invoke(ActionInvocation("ban.check", listOf(target), ActionSource.ADDON))
        val line = result.lines.firstOrNull()
        if (!result.success || line == null) {
            return msg.formatFor(executor, "modlookup.na")
        }
        return if (line.contains("not banned", ignoreCase = true)) msg.formatFor(executor, "modlookup.none") else line
    }

    /** Mute status for `/modlookup`, read directly from this addon's own [MuteStore]. */
    private fun lookupMute(executor: String, target: String): String {
        val entry = mutes.activeMute(target) ?: return msg.formatFor(executor, "modlookup.none")
        return "${entry.reason} (${expiryText(entry.expiresAtEpochMs)})"
    }

    /**
     * Guard incident count for `/modlookup`: Guard only exposes uuid-keyed
     * queries, so this scans the network-wide recent-incidents ring
     * (`guard.query.incidents all <limit>`, capped network-wide, newest
     * first) for name matches — a "recent" count, not a lifetime total, and
     * `modlookup.na` when the Guard addon is not installed at all.
     */
    private fun lookupIncidentCount(executor: String, target: String): String {
        val result = context.actions.invoke(
            ActionInvocation("guard.query.incidents", listOf("all", "200"), ActionSource.ADDON),
        )
        val payload = result.lines.firstOrNull()
        if (!result.success || payload == null) {
            return msg.formatFor(executor, "modlookup.na")
        }
        val incidents = runCatching {
            json.parseToJsonElement(payload).jsonObject["incidents"]?.jsonArray
        }.getOrNull() ?: return "0"
        return incidents.count { element ->
            element.jsonObject["name"]?.jsonPrimitive?.content.equals(target, ignoreCase = true)
        }.toString()
    }

    /**
     * Publishes the active mute map (lowercase player to expiry epoch millis,
     * `0` for permanent) so bridges can enforce mutes at the chat-send point
     * without a per-message round trip.
     */
    private fun publishMutes() {
        val active = mutes.all().associate { it.player to (it.expiresAtEpochMs ?: 0L) }
        context.publishBridgeValue("moderation.mutes", json.encodeToString(active))
    }

    /** Publishes the configured chat blocklist for bridge-side enforcement. */
    private fun publishBlocklist() {
        context.publishBridgeValue("moderation.blocklist", json.encodeToString(config.blockedWords))
    }

    /**
     * Publishes the static bilingual texts a bridge shows when it blocks a
     * chat message (mute or filter hit). Chat is rendered fully bridge-side
     * with no per-message round trip, so these cannot be resolved through
     * the per-player [msg] language lookup like every other moderation
     * message here — the bridge instead picks by the player's own reported
     * client locale (see `HelixPaperBridgePlugin`).
     */
    private fun publishMuteMessages() {
        context.publishBridgeValue(
            "moderation.muteMessage",
            json.encodeToString(
                mapOf(
                    "en" to "&cYou are muted and cannot chat.",
                    "de" to "&cDu bist stummgeschaltet und kannst nicht schreiben.",
                ),
            ),
        )
        context.publishBridgeValue(
            "moderation.blockedMessage",
            json.encodeToString(
                mapOf(
                    "en" to "&cYour message was blocked: it contains a banned word.",
                    "de" to "&cDeine Nachricht wurde blockiert: sie enthält ein gesperrtes Wort.",
                ),
            ),
        )
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

    private companion object {
        /** Document key holding the persisted [ModerationConfig]. */
        const val CONFIG_DOCUMENT = "config"
    }
}
