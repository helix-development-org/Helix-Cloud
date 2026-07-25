package org.helix.addons.discord

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionSource
import org.helix.api.message.MapMessages
import org.helix.api.message.Messages

/**
 * Kord-free command logic of the Discord bot, unit-testable without a
 * gateway connection.
 *
 * Supported commands (with the configured prefix):
 * - `!status` — platform overview,
 * - `!players` — online players,
 * - `!help` — command list,
 * - `!run <action> [args...]` — any platform action, restricted to the
 *   configured admin user ids.
 *
 * @property actions action entry point of the node.
 * @property config supplies the current configuration.
 * @property messages configurable reply templates.
 */
class DiscordCommandHandler(
    private val actions: ActionInvoker,
    private val config: () -> DiscordConfig,
    private val messages: Messages = MapMessages(DEFAULT_MESSAGES.getValue("en")),
) {
    /**
     * Handles one Discord message.
     *
     * @param authorId Discord user id of the author.
     * @param authorIsBot whether the author is a bot.
     * @param channelId channel the message was posted in.
     * @param content raw message content.
     * @return the reply to post, or `null` when the message is ignored.
     */
    fun handle(authorId: String, authorIsBot: Boolean, channelId: String, content: String): String? {
        val current = config()
        if (authorIsBot || channelId != current.channelId || !content.startsWith(current.commandPrefix)) {
            return null
        }
        val tokens = content.removePrefix(current.commandPrefix).trim().split(Regex("\\s+"))
        return when (tokens.firstOrNull()?.lowercase()) {
            "status" -> reply(invoke("platform.overview"))
            "players" -> reply(invoke("player.list"))
            "help" -> codeBlock(
                listOf(
                    "${current.commandPrefix}status  — platform overview",
                    "${current.commandPrefix}players — online players",
                    "${current.commandPrefix}run <action> [args...] — run a platform action (admins)",
                ),
            )
            "run" -> runAction(authorId, tokens.drop(1), current)
            else -> null
        }
    }

    /**
     * Strips `&` color codes for Discord output.
     *
     * @param text minecraft-formatted text.
     * @return plain text.
     */
    fun stripColors(text: String): String = text.replace(Regex("&[0-9a-fk-orA-FK-OR]"), "")

    private fun runAction(authorId: String, tokens: List<String>, current: DiscordConfig): String {
        if (authorId !in current.adminUserIds) {
            return messages.raw("run.denied")
        }
        val action = tokens.firstOrNull() ?: return "Usage: ${current.commandPrefix}run <action> [args...]"
        return reply(
            actions.invoke(
                ActionInvocation(action, tokens.drop(1), ActionSource.ADDON),
            ),
        )
    }

    private fun invoke(action: String) =
        actions.invoke(ActionInvocation(action, emptyList(), ActionSource.ADDON))

    private fun reply(result: org.helix.api.action.ActionResult): String {
        val lines = result.lines.map(::stripColors)
            .ifEmpty { listOf(if (result.success) "done" else "failed") }
        return codeBlock(lines)
    }

    private fun codeBlock(lines: List<String>): String =
        "```\n${lines.joinToString("\n")}\n```"

    companion object {
        /** Default configurable reply templates of the Discord bot, by language code. */
        val DEFAULT_MESSAGES = mapOf(
            "en" to mapOf(
                "run.denied" to "You are not allowed to run actions.",
                "unavailable" to "Command is currently unavailable.",
            ),
            "de" to mapOf(
                "run.denied" to "Du darfst keine Aktionen ausführen.",
                "unavailable" to "Der Befehl ist gerade nicht verfügbar.",
            ),
        )
    }
}
