package org.helix.addons.discord

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Builds the Discord audit trail of everything humans trigger on the
 * network and routes each entry to its configured channel.
 *
 * Covered, per the audit scope: every action invocation with a human
 * actor (Discord users, dashboard/CLI admins, in-game player commands),
 * denied Discord accesses, the confirmation lifecycle (cancelled, expired,
 * mismatched type-to-confirm) and account link/unlink events. Node-internal
 * invocations ([ActionSource.SYSTEM], [ActionSource.ADDON]) and machine
 * bridge calls stay out; the bot's own executions are reported through
 * [discordAction] with richer attribution instead.
 *
 * Formatting is deliberately Kord-free: entries are plain markdown lines
 * handed to [sink], which the bot runtime batches per channel.
 *
 * @property config supplies the current configuration for routing.
 * @property texts translation helper; audit entries use the network's
 *   default language.
 * @property descriptorOf resolves an action name to its descriptor, to
 *   tell player commands apart from machine bridge calls.
 * @property sink receives (channel id, markdown line, accent color) —
 *   the runtime renders every entry as a Components-V2 container, tinted
 *   per event type.
 */
class AuditLog(
    private val config: () -> DiscordConfig,
    private val texts: DiscordMessages,
    private val descriptorOf: (String) -> ActionDescriptor?,
    private val sink: (channelId: String, text: String, accent: Int?) -> Unit,
) {
    /**
     * Observes a node-side action execution and logs it when a human
     * triggered it: the CLI, an authenticated panel session or token, or a
     * player running an in-game command through a bridge.
     *
     * @param invocation the executed invocation.
     * @param result the outcome.
     */
    fun observe(invocation: ActionInvocation, result: ActionResult) {
        val descriptor = descriptorOf(invocation.action)
        val actor = when (invocation.source) {
            ActionSource.CLI -> invocation.actor ?: "CLI"
            ActionSource.REST -> invocation.actor ?: texts.t("audit.actor.panel")
            ActionSource.BRIDGE ->
                if (descriptor?.playerCommand == true) {
                    invocation.arguments.firstOrNull() ?: return
                } else {
                    return
                }
            ActionSource.ADDON, ActionSource.SYSTEM -> return
        }
        val arguments = when {
            descriptor?.playerCommand == true -> invocation.arguments.drop(1)
            else -> invocation.arguments
        }
        emit(
            TYPE_ACTION,
            texts.t(
                "audit.action",
                "icon" to icon(result.success),
                "action" to invocation.action,
                "args" to formatArguments(invocation.action, arguments),
                "actor" to actor,
                "source" to sourceLabel(invocation.source),
            ),
        )
    }

    /**
     * Logs an action the bot executed on behalf of a Discord user.
     *
     * @param discordName the Discord user name.
     * @param actorName the linked Minecraft name.
     * @param action the executed action.
     * @param arguments the action arguments.
     * @param result the outcome.
     */
    fun discordAction(
        discordName: String,
        actorName: String,
        action: String,
        arguments: List<String>,
        result: ActionResult,
    ) {
        emit(
            TYPE_ACTION,
            texts.t(
                "audit.action",
                "icon" to icon(result.success),
                "action" to action,
                "args" to formatArguments(action, arguments),
                "actor" to "$actorName ($discordName)",
                "source" to "Discord",
            ),
        )
    }

    /**
     * Logs a denied Discord access — who wanted what and did not get
     * through.
     *
     * @param discordName the Discord user name.
     * @param action the attempted action or feature.
     * @param reason denial reason: the missing node, or `not linked`.
     */
    fun denied(discordName: String, action: String, reason: String) {
        emit(
            TYPE_DENIED,
            texts.t("audit.denied", "user" to discordName, "action" to action, "reason" to reason),
        )
    }

    /**
     * Logs a confirmation that did not result in an execution.
     *
     * @param kind what happened: `cancelled`, `expired` or `mismatch`.
     * @param pending the pending action.
     * @param discordName the Discord user name.
     */
    fun confirmation(kind: String, pending: PendingConfirmation, discordName: String) {
        emit(
            TYPE_CONFIRMATION,
            texts.t(
                "audit.confirmation.$kind",
                "user" to "${pending.actorName} ($discordName)",
                "action" to pending.action,
                "args" to formatArguments(pending.action, pending.arguments),
            ),
        )
    }

    /**
     * Logs an account link or unlink event.
     *
     * @param key message key under `audit.link.`, for example `created`.
     * @param link the affected link.
     * @param via how the event came about, for the trail.
     */
    fun link(key: String, link: DiscordLink, via: String) {
        emit(
            TYPE_LINK,
            texts.t(
                "audit.link.$key",
                "player" to link.playerName,
                "discord" to link.discordName,
                "discordId" to link.discordId,
                "via" to via,
            ),
        )
    }

    private fun emit(type: String, text: String) {
        val channel = config().channelForAudit(type)
        if (channel.isNotBlank()) {
            sink(channel, text, TONES[type])
        }
    }

    private fun icon(success: Boolean): String = if (success) "✅" else "❌"

    private fun sourceLabel(source: ActionSource): String = when (source) {
        ActionSource.CLI -> "CLI"
        ActionSource.REST -> "Panel"
        ActionSource.BRIDGE -> "Ingame"
        ActionSource.ADDON -> "Addon"
        ActionSource.SYSTEM -> "System"
    }

    private fun formatArguments(action: String, arguments: List<String>): String {
        val joined = arguments.joinToString(" ")
        val safe = if (action in SECRET_ARGUMENT_ACTIONS) "•••" else DiscordMessages.stripColors(joined)
        return if (safe.isBlank()) "" else "`${DiscordMessages.truncate(safe, 120)}`"
    }

    companion object {
        /** Audit event type of executed actions. */
        const val TYPE_ACTION = "action"

        /** Audit event type of denied accesses. */
        const val TYPE_DENIED = "denied"

        /** Audit event type of the confirmation lifecycle. */
        const val TYPE_CONFIRMATION = "confirmation"

        /** Audit event type of link/unlink events. */
        const val TYPE_LINK = "link"

        /** Actions whose arguments carry secrets and are masked. */
        val SECRET_ARGUMENT_ACTIONS = setOf("discord.config.set")

        /** Container accent color per audit event type. */
        val TONES = mapOf(
            TYPE_DENIED to 0xED4245,
            TYPE_CONFIRMATION to 0xFEE75C,
            TYPE_LINK to 0x5865F2,
        )
    }
}
