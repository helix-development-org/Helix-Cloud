package org.helix.addons.bettermsgs

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Private-message persistence backed by the addon's document storage.
 *
 * One conversation document per player pair (key `conv.<a>|<b>` with the
 * lowercase names sorted alphabetically, capped at the newest
 * [MAX_MESSAGES] entries) plus one contact-index document per player
 * (key `contacts.<player>`, peer name to [ContactEntry]).
 *
 * All mutating and reading methods are synchronized because actions may be
 * invoked concurrently.
 *
 * @property storage addon-scoped document store.
 */
class MessageStore(private val storage: AddonStorage) {
    private val json = Json

    /**
     * Appends a message to the pair's conversation and bumps both contact
     * indexes: the sender's entry for the recipient and the recipient's
     * entry for the sender get [epochMs] as last activity, and the
     * recipient's unread counter for the sender is incremented.
     *
     * The conversation is capped at the newest [MAX_MESSAGES] messages.
     *
     * @param from sender name, any case.
     * @param to recipient name, any case.
     * @param text raw message text.
     * @param epochMs server timestamp of the message, epoch milliseconds.
     */
    @Synchronized
    fun append(from: String, to: String, text: String, epochMs: Long) {
        val key = conversationKey(from, to)
        val messages = readConversation(key) + ChatMessage(from.lowercase(), text, epochMs)
        storage.write(key, json.encodeToString(messages.takeLast(MAX_MESSAGES)))
        bumpContact(from, to, epochMs, incrementUnread = false)
        bumpContact(to, from, epochMs, incrementUnread = true)
    }

    /**
     * Reads the full conversation of a player pair.
     *
     * @param a first player, any case.
     * @param b second player, any case.
     * @return all stored messages, oldest first; empty when the pair never
     *   exchanged messages.
     */
    @Synchronized
    fun history(a: String, b: String): List<ChatMessage> =
        readConversation(conversationKey(a, b))

    /**
     * Reads a player's contact index.
     *
     * @param player the player, any case.
     * @return peer name (lowercase) to contact entry; empty when the
     *   player has no contacts.
     */
    @Synchronized
    fun contacts(player: String): Map<String, ContactEntry> =
        readContacts(contactsKey(player))

    /**
     * Resets the unread counter of a peer in a player's contact index.
     *
     * A no-op when the peer is not in the index.
     *
     * @param player the reading player, any case.
     * @param peer the peer whose messages were read, any case.
     */
    @Synchronized
    fun markRead(player: String, peer: String) {
        val key = contactsKey(player)
        val contacts = readContacts(key).toMutableMap()
        val entry = contacts[peer.lowercase()] ?: return
        if (entry.unread == 0) {
            return
        }
        contacts[peer.lowercase()] = entry.copy(unread = 0)
        storage.write(key, json.encodeToString(contacts.toMap()))
    }

    private fun bumpContact(owner: String, peer: String, epochMs: Long, incrementUnread: Boolean) {
        val key = contactsKey(owner)
        val contacts = readContacts(key).toMutableMap()
        val previous = contacts[peer.lowercase()]
        contacts[peer.lowercase()] = ContactEntry(
            lastEpochMs = epochMs,
            unread = (previous?.unread ?: 0) + if (incrementUnread) 1 else 0,
        )
        storage.write(key, json.encodeToString(contacts.toMap()))
    }

    private fun readConversation(key: String): List<ChatMessage> =
        storage.read(key)?.let { json.decodeFromString<List<ChatMessage>>(it) } ?: emptyList()

    private fun readContacts(key: String): Map<String, ContactEntry> =
        storage.read(key)?.let { json.decodeFromString<Map<String, ContactEntry>>(it) } ?: emptyMap()

    private fun conversationKey(a: String, b: String): String {
        val pair = listOf(a.lowercase(), b.lowercase()).sorted()
        return "conv.${pair[0]}|${pair[1]}"
    }

    private fun contactsKey(player: String): String = "contacts.${player.lowercase()}"

    private companion object {
        /** Maximum number of messages kept per conversation, newest win. */
        const val MAX_MESSAGES = 500
    }
}
