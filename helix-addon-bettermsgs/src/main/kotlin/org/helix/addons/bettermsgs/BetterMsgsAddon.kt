package org.helix.addons.bettermsgs

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

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
    private lateinit var msg: org.helix.api.message.Messages
    private val json = Json

    /** Player (lowercase) to the peer (lowercase) whose chat is open. */
    private val focus = ConcurrentHashMap<String, String>()

    /**
     * Registers the `bettermsgs.*` control-API actions.
     */
    override fun enable() {
        store = MessageStore(context.storage())
        msg = context.localizedMessages(
            mapOf(
                "en" to mapOf(
                    "notify" to "{prefix} <gray>New message from <white>{sender}</white> — " +
                        "<click:run_command:'/msg {sender}'><aqua>[open]</aqua></click>",
                    "error.self" to "&cYou cannot message yourself.",
                    // GUI texts of the Paper component (resolved per player there)
                    "item.back" to "<gray>Back",
                    "item.close" to "<red>Close",
                    "item.write" to "<green>Write a message…",
                    "item.scroll.up" to "<gray>Older",
                    "item.scroll.down" to "<gray>Newer",
                    "item.refresh" to "<gray>Refresh",
                    "prompt.message" to "<gray>Type your message in chat (or <white>cancel</white>):",
                    "note.empty" to "<gray>No messages yet — say hi!",
                    "note.offline" to "<dark_gray>offline",
                    "note.online" to "<green>online",
                    "sent" to "<gray>To <white>{target}</white>: {text}",
                ),
                "de" to mapOf(
                    "notify" to "{prefix} <gray>Neue Nachricht von <white>{sender}</white> — " +
                        "<click:run_command:'/msg {sender}'><aqua>[öffnen]</aqua></click>",
                    "error.self" to "&cDu kannst dir nicht selbst schreiben.",
                    "item.back" to "<gray>Zurück",
                    "item.close" to "<red>Schließen",
                    "item.write" to "<green>Nachricht schreiben…",
                    "item.scroll.up" to "<gray>Ältere",
                    "item.scroll.down" to "<gray>Neuere",
                    "item.refresh" to "<gray>Aktualisieren",
                    "prompt.message" to "<gray>Schreib deine Nachricht in den Chat (oder <white>cancel</white>):",
                    "note.empty" to "<gray>Noch keine Nachrichten — sag hallo!",
                    "note.offline" to "<dark_gray>offline",
                    "note.online" to "<green>online",
                    "sent" to "<gray>An <white>{target}</white>: {text}",
                ),
            ),
        )
        action(
            "bettermsgs.send",
            "Appends a private message and notifies the recipient.",
            "bettermsgs.send <from> <to> <text...>",
        ) { invocation -> send(invocation) }
        action(
            "bettermsgs.history",
            "Reads a window of a conversation, offset counted from the newest message.",
            "bettermsgs.history <a> <b> <offset> <limit>",
        ) { invocation -> history(invocation) }
        action(
            "bettermsgs.contacts",
            "Lists a player's contacts with unread counts and online status.",
            "bettermsgs.contacts <player>",
        ) { invocation -> contacts(invocation) }
        action(
            "bettermsgs.read",
            "Resets the unread counter of a peer in a player's contact index.",
            "bettermsgs.read <player> <peer>",
        ) { invocation -> read(invocation) }
        action(
            "bettermsgs.focus",
            "Records which conversation a player has open; '-' clears the focus.",
            "bettermsgs.focus <player> <peer|->",
        ) { invocation -> setFocus(invocation) }
        action(
            "bettermsgs.packurl",
            "Sets the public resource-pack URL clients download; '-' resets to auto.",
            "bettermsgs.packurl <url|->",
        ) { invocation -> setPackUrl(invocation) }
        publishPackUrl()
    }

    private fun setPackUrl(invocation: ActionInvocation): ActionResult {
        val url = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("usage: bettermsgs.packurl <url|->")
        if (url == "-") {
            context.storage().delete(PACK_URL_DOCUMENT)
        } else {
            context.storage().write(PACK_URL_DOCUMENT, url)
        }
        publishPackUrl()
        return ActionResult.ok(if (url == "-") "pack url reset to auto" else "pack url set to $url")
    }

    /**
     * Publishes the configured pack URL as a bridge value, so the Paper
     * component can prefer it over the auto-detected address.
     */
    private fun publishPackUrl() {
        context.storage().read(PACK_URL_DOCUMENT)?.let { url ->
            context.publishBridgeValue("bettermsgs.pack_url", url)
        }
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
        if (from.equals(to, ignoreCase = true)) {
            return ActionResult.error(msg.formatFor(from, "error.self"))
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

        /** Storage document holding the configured public pack URL. */
        const val PACK_URL_DOCUMENT = "packurl"
    }
}
