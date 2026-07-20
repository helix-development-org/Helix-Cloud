package org.helix.addons.friends

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Friendship persistence backed by the addon's document storage.
 *
 * @property storage addon-scoped document store.
 */
class FriendStore(private val storage: AddonStorage) {
    private val json = Json { prettyPrint = true }
    private val friendships = mutableSetOf<Set<String>>()
    private val requests = mutableMapOf<String, MutableSet<String>>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            val document = json.decodeFromString<FriendDocument>(raw)
            document.friendships.forEach { friendships += it.toSet() }
            document.requests.forEach { (to, from) -> requests[to] = from.toMutableSet() }
        }
    }

    /**
     * Whether two players are friends.
     *
     * @param a first player.
     * @param b second player.
     * @return `true` when a friendship exists.
     */
    @Synchronized
    fun areFriends(a: String, b: String): Boolean =
        setOf(a.lowercase(), b.lowercase()) in friendships

    /**
     * Records a friend request.
     *
     * @param from requesting player.
     * @param to requested player.
     * @return `false` when the request already existed.
     */
    @Synchronized
    fun request(from: String, to: String): Boolean {
        val added = requests.getOrPut(to.lowercase()) { mutableSetOf() }.add(from.lowercase())
        if (added) {
            persist()
        }
        return added
    }

    /**
     * Whether a pending request exists.
     *
     * @param from requesting player.
     * @param to requested player.
     * @return `true` when pending.
     */
    @Synchronized
    fun hasRequest(from: String, to: String): Boolean =
        requests[to.lowercase()]?.contains(from.lowercase()) == true

    /**
     * Accepts a pending request and creates the friendship.
     *
     * @param to accepting player.
     * @param from original requester.
     * @return `false` when no request was pending.
     */
    @Synchronized
    fun accept(to: String, from: String): Boolean {
        if (!removeRequest(from, to)) {
            return false
        }
        friendships += setOf(from.lowercase(), to.lowercase())
        persist()
        return true
    }

    /**
     * Denies a pending request.
     *
     * @param to denying player.
     * @param from original requester.
     * @return `false` when no request was pending.
     */
    @Synchronized
    fun deny(to: String, from: String): Boolean = removeRequest(from, to).also { if (it) persist() }

    /**
     * Removes an existing friendship.
     *
     * @param a first player.
     * @param b second player.
     * @return `false` when they were not friends.
     */
    @Synchronized
    fun remove(a: String, b: String): Boolean {
        val removed = friendships.remove(setOf(a.lowercase(), b.lowercase()))
        if (removed) {
            persist()
        }
        return removed
    }

    /**
     * Lists a player's friends.
     *
     * @param player the player.
     * @return friend names sorted alphabetically.
     */
    @Synchronized
    fun friendsOf(player: String): List<String> {
        val key = player.lowercase()
        return friendships.filter { key in it }.map { (it - key).single() }.sorted()
    }

    /**
     * Lists pending requests for a player.
     *
     * @param player the requested player.
     * @return requester names sorted alphabetically.
     */
    @Synchronized
    fun requestsFor(player: String): List<String> =
        requests[player.lowercase()]?.sorted() ?: emptyList()

    private fun removeRequest(from: String, to: String): Boolean {
        val set = requests[to.lowercase()] ?: return false
        val removed = set.remove(from.lowercase())
        if (set.isEmpty()) {
            requests.remove(to.lowercase())
        }
        return removed
    }

    private fun persist() {
        storage.write(
            DOCUMENT,
            json.encodeToString(
                FriendDocument(
                    friendships = friendships.map { it.toList().sorted() },
                    requests = requests.mapValues { it.value.toSet() },
                ),
            ),
        )
    }

    private companion object {
        /** Document key holding the friendship state. */
        const val DOCUMENT = "friends"
    }
}
