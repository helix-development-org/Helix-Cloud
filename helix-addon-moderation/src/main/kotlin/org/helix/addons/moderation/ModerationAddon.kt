package org.helix.addons.moderation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * A recorded warning.
 *
 * @property player warned player, lowercase.
 * @property by warning moderator.
 * @property reason warning reason.
 * @property atEpochMs when the warning was issued.
 */
@Serializable
data class WarnEntry(
    val player: String,
    val by: String,
    val reason: String,
    val atEpochMs: Long,
)

/**
 * JSON-file backed warn history.
 *
 * @property file the `warns.json` path.
 * @property clock epoch millis source, injectable for tests.
 */
class WarnStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val warns = mutableListOf<WarnEntry>()

    init {
        if (Files.exists(file)) {
            warns += json.decodeFromString<List<WarnEntry>>(Files.readString(file))
        }
    }

    /**
     * Records a warning.
     *
     * @param player warned player.
     * @param by warning moderator.
     * @param reason warning reason.
     * @return the persisted entry.
     */
    @Synchronized
    fun warn(player: String, by: String, reason: String): WarnEntry {
        val entry = WarnEntry(player.lowercase(), by, reason, clock())
        warns += entry
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(warns.toList()))
        return entry
    }

    /**
     * Lists all warnings of a player, newest first.
     *
     * @param player the player.
     * @return warnings sorted by time descending.
     */
    @Synchronized
    fun warnsOf(player: String): List<WarnEntry> =
        warns.filter { it.player == player.lowercase() }.sortedByDescending { it.atEpochMs }
}

/**
 * Moderation addon.
 *
 * Permission-gated in-game commands for moderators: `/kick`, `/warn`,
 * `/warns`, `/announce` and `/tempban` (delegating to the ban addon when
 * installed). Enforcement runs entirely through generic platform actions.
 */
class ModerationAddon : AddonBase() {
    private lateinit var store: WarnStore

    /**
     * Registers the moderation player commands.
     */
    override fun enable() {
        store = WarnStore(context.dataDirectory.resolve("warns.json"))
        playerCommand(
            "kick",
            "Kicks a player from the network.",
            "kick <player> [reason...]",
            "helix.mod.kick",
        ) { executor, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage("/kick <player> [reason...]")
            val reason = args.drop(1).joinToString(" ").ifBlank { "Kicked by a moderator." }
            val result = invoke("player.kick", target, "$reason &7(by $executor)")
            if (result.success) {
                context.publishNotification("moderation", "&c[Kick] &f$target &7by $executor: $reason")
                ActionResult.ok("&7Kicked &f$target&7.")
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
            val target = args.firstOrNull() ?: return@playerCommand usage("/warn <player> <reason...>")
            val reason = args.drop(1).joinToString(" ").ifBlank { return@playerCommand usage("/warn <player> <reason...>") }
            store.warn(target, executor, reason)
            invoke("player.message", target, "&cYou have been warned: &f$reason")
            val total = store.warnsOf(target).size
            context.publishNotification("moderation", "&e[Warn] &f$target &7by $executor: $reason ($total total)")
            ActionResult.ok("&7Warned &f$target&7: $reason ($total total)")
        }
        playerCommand(
            "warns",
            "Shows a player's warn history.",
            "warns <player>",
            "helix.mod.warn",
        ) { _, args ->
            val target = args.firstOrNull() ?: return@playerCommand usage("/warns <player>")
            val history = store.warnsOf(target)
            if (history.isEmpty()) {
                ActionResult.ok("&7$target has no warnings.")
            } else {
                ActionResult.ok(
                    *history.map { "&c${it.reason} &7— by ${it.by}" }.toTypedArray(),
                )
            }
        }
        playerCommand(
            "announce",
            "Broadcasts an announcement to the whole network.",
            "announce <text...>",
            "helix.mod.broadcast",
        ) { _, args ->
            val text = args.joinToString(" ")
            if (text.isBlank()) {
                usage("/announce <text...>")
            } else {
                invoke("player.broadcast", "&c[Announcement] &f$text")
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
                usage("/tempban <player> <duration> [reason...]")
            } else {
                val reason = args.drop(2).joinToString(" ").ifBlank { "Banned by $executor" }
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

    private fun usage(text: String): ActionResult = ActionResult.error("Usage: $text")
}
