package org.helix.addons.bettermsgs

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.message.Messages
import java.util.concurrent.ConcurrentHashMap

/**
 * Private-messaging backend addon ("BetterMSGs").
 *
 * Persists conversations and per-player contact indexes and exposes the
 * `bettermsgs.*` control-API actions consumed by the separate BetterMSGs
 * Paper GUI plugin. No GUI or player command lives here; recipients only
 * receive an in-game click-to-open notification (via the generic
 * `player.message` action) when they get a message while not focused on
 * that conversation.
 */
class BetterMsgsAddon : AddonBase() {
    private lateinit var store: MessageStore
    private lateinit var msg: Messages
    private val json = Json

    /** Player (lowercase) to the peer (lowercase) whose chat is open. */
    private val focus = ConcurrentHashMap<String, String>()

    /**
     * Registers the `bettermsgs.*` control-API actions.
     */
    override fun enable() {
        store = MessageStore(context.storage())
        msg = loadMessages()
        action(
            "bettermsgs.send",
            "Appends a private message and notifies the recipient.",
            "bettermsgs.send <from> <to> <text...>",
            bridgeInvocable = true,
        ) { invocation -> send(invocation) }
        action(
            "bettermsgs.history",
            "Reads a window of a conversation, offset counted from the newest message.",
            "bettermsgs.history <a> <b> <offset> <limit>",
            bridgeInvocable = true,
        ) { invocation -> history(invocation) }
        action(
            "bettermsgs.contacts",
            "Lists a player's contacts with unread counts and online status.",
            "bettermsgs.contacts <player>",
            bridgeInvocable = true,
        ) { invocation -> contacts(invocation) }
        action(
            "bettermsgs.read",
            "Resets the unread counter of a peer in a player's contact index.",
            "bettermsgs.read <player> <peer>",
            bridgeInvocable = true,
        ) { invocation -> read(invocation) }
        action(
            "bettermsgs.focus",
            "Records which conversation a player has open; '-' clears the focus.",
            "bettermsgs.focus <player> <peer|->",
            bridgeInvocable = true,
        ) { invocation -> setFocus(invocation) }
    }

    private fun send(invocation: ActionInvocation): ActionResult {
        val from = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: bettermsgs.send <from> <to> <text...>")
        val to = invocation.arguments.getOrNull(1)
            ?: return ActionResult.error("usage: bettermsgs.send <from> <to> <text...>")
        val text = invocation.arguments.drop(2).joinToString(" ")
        if (text.isBlank()) {
            return ActionResult.error("usage: bettermsgs.send <from> <to> <text...>")
        }
        val epochMs = System.currentTimeMillis()
        store.append(from, to, text, epochMs)
        val recipientFocused = focus[to.lowercase()] == from.lowercase()
        val recipientOnline = context.onlinePlayers().any { it.name.equals(to, ignoreCase = true) }
        if (!recipientFocused && recipientOnline) {
            context.actions.invoke(
                ActionInvocation(
                    "player.message",
                    listOf(to, msg.formatFor(to, "notify", "sender" to from)),
                    ActionSource.ADDON,
                ),
            )
        }
        return ActionResult.ok(json.encodeToString(SendResponse(ok = true, epochMs = epochMs)))
    }

    private fun history(invocation: ActionInvocation): ActionResult {
        val a = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: bettermsgs.history <a> <b> <offset> <limit>")
        val b = invocation.arguments.getOrNull(1)
            ?: return ActionResult.error("usage: bettermsgs.history <a> <b> <offset> <limit>")
        val offset = invocation.arguments.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 }
            ?: return ActionResult.error("usage: bettermsgs.history <a> <b> <offset> <limit>")
        val limit = invocation.arguments.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
            ?.coerceAtMost(MAX_HISTORY_LIMIT)
            ?: return ActionResult.error("usage: bettermsgs.history <a> <b> <offset> <limit>")
        val messages = store.history(a, b)
        val end = (messages.size - offset).coerceAtLeast(0)
        val start = (end - limit).coerceAtLeast(0)
        return ActionResult.ok(
            json.encodeToString(
                HistoryResponse(
                    total = messages.size,
                    offset = offset,
                    messages = messages.subList(start, end),
                ),
            ),
        )
    }

    private fun contacts(invocation: ActionInvocation): ActionResult {
        val player = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: bettermsgs.contacts <player>")
        val online = context.onlinePlayers().map { it.name.lowercase() }.toSet()
        val entries = store.contacts(player).entries
            .sortedByDescending { it.value.lastEpochMs }
            .map { (peer, entry) ->
                ContactView(
                    name = peer,
                    lastEpochMs = entry.lastEpochMs,
                    unread = entry.unread,
                    online = peer in online,
                )
            }
        return ActionResult.ok(json.encodeToString(entries))
    }

    private fun read(invocation: ActionInvocation): ActionResult {
        val player = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: bettermsgs.read <player> <peer>")
        val peer = invocation.arguments.getOrNull(1)
            ?: return ActionResult.error("usage: bettermsgs.read <player> <peer>")
        store.markRead(player, peer)
        return ok()
    }

    private fun setFocus(invocation: ActionInvocation): ActionResult {
        val player = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: bettermsgs.focus <player> <peer|->")
        val peer = invocation.arguments.getOrNull(1)
            ?: return ActionResult.error("usage: bettermsgs.focus <player> <peer|->")
        if (peer == "-") {
            focus.remove(player.lowercase())
        } else {
            focus[player.lowercase()] = peer.lowercase()
            store.markRead(player, peer)
        }
        return ok()
    }

    private fun ok(): ActionResult = ActionResult.ok(json.encodeToString(OkResponse(ok = true)))

    private companion object {
        /** Maximum window size a single history request may return. */
        const val MAX_HISTORY_LIMIT = 50
    }
}
