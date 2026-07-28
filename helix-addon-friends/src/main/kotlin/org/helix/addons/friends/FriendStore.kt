package org.helix.addons.friends

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Outcome of [FriendStore.request].
 */
enum class FriendRequestOutcome {
    /** A new request was recorded. */
    SENT,

    /** A request from this sender to this target is already pending. */
    ALREADY_PENDING,

    /** The sender must wait out [FriendStore]'s cooldown before retrying this target. */
    COOLDOWN,
}

/**
 * Friendship persistence backed by the addon's document storage.
 *
 * Friendships and requests are keyed on identity keys — a player's uuid once
 * known, otherwise their lowercase name as a fallback for players this node
 * has never seen join. A legacy name-keyed entry is migrated to its uuid the
 * first time that uuid becomes resolvable, so a rename cannot be used to
 * dodge an existing friendship or pending request.
 *
 * Request cooldown is not persisted (it resets on restart): losing a few
 * seconds of an in-progress cooldown window on the rare service restart is
 * harmless, and skipping it keeps the storage schema unchanged.
 *
 * @property storage addon-scoped document store.
 * @property resolveUuid resolves a player name to its current owner's uuid,
 *  typically the node's identity registry via `AddonContext.resolvePlayerUuid`.
 * @property cooldownMillis minimum time a sender must wait before
 *   re-requesting the same target once no request is pending (blocks
 *   request/deny/re-request spam used to repeatedly ping a victim).
 * @property clock epoch millis source, injectable for tests.
 */
class FriendStore(
    private val storage: AddonStorage,
    private val resolveUuid: (String) -> String? = { null },
    private val cooldownMillis: Long = DEFAULT_REQUEST_COOLDOWN_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val friendships = mutableSetOf<Set<String>>()
    private val requests = mutableMapOf<String, MutableSet<String>>()
    private val displayNames = mutableMapOf<String, String>()
    private val lastRequestAt = mutableMapOf<String, Long>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            val document = json.decodeFromString<FriendDocument>(raw)
            document.friendships.forEach { friendships += it.toSet() }
            document.requests.forEach { (to, from) -> requests[to] = from.toMutableSet() }
            displayNames += document.displayNames
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
        setOf(keyOf(a), keyOf(b)) in friendships

    /**
     * Records a friend request, subject to the per-sender/target cooldown.
     *
     * @param from requesting player.
     * @param to requested player.
     * @return the outcome (sent, already pending, or on cooldown).
     */
    @Synchronized
    fun request(from: String, to: String): FriendRequestOutcome {
        val key = requestKey(from, to)
        val now = clock()
        val last = lastRequestAt[key]
        if (last != null && now - last < cooldownMillis) {
            return FriendRequestOutcome.COOLDOWN
        }
        val added = requests.getOrPut(keyOf(to)) { mutableSetOf() }.add(keyOf(from))
        if (!added) {
            return FriendRequestOutcome.ALREADY_PENDING
        }
        lastRequestAt[key] = now
        persist()
        return FriendRequestOutcome.SENT
    }

    private fun requestKey(from: String, to: String) = "${from.lowercase()}|${to.lowercase()}"

    /**
     * Whether a pending request exists.
     *
     * @param from requesting player.
     * @param to requested player.
     * @return `true` when pending.
     */
    @Synchronized
    fun hasRequest(from: String, to: String): Boolean =
        requests[keyOf(to)]?.contains(keyOf(from)) == true

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
        friendships += setOf(keyOf(from), keyOf(to))
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
        val removed = friendships.remove(setOf(keyOf(a), keyOf(b)))
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
        val key = keyOf(player)
        return friendships.filter { key in it }.map { nameOf((it - key).single()) }.sorted()
    }

    /**
     * Lists pending requests for a player.
     *
     * @param player the requested player.
     * @return requester names sorted alphabetically.
     */
    @Synchronized
    fun requestsFor(player: String): List<String> =
        requests[keyOf(player)]?.map(::nameOf)?.sorted() ?: emptyList()

    /**
     * Removes every trace of a player: all their friendships and every
     * pending request in either direction. Used by GDPR delete requests.
     *
     * @param player the player.
     * @return `true` when anything was actually removed.
     */
    @Synchronized
    fun forget(player: String): Boolean {
        val key = player.lowercase()
        val removedFriendships = friendships.removeAll { key in it }
        val removedIncoming = requests.remove(key) != null
        val removedOutgoing = requests.entries.toList().fold(false) { changed, (to, from) ->
            val had = from.remove(key)
            if (had && from.isEmpty()) {
                requests.remove(to)
            }
            changed || had
        }
        val changed = removedFriendships || removedIncoming || removedOutgoing
        if (changed) {
            persist()
        }
        return changed
    }

    private fun removeRequest(from: String, to: String): Boolean {
        val toKey = keyOf(to)
        val set = requests[toKey] ?: return false
        val removed = set.remove(keyOf(from))
        if (set.isEmpty()) {
            requests.remove(toKey)
        }
        return removed
    }

    /**
     * Resolves the identity key of a player name — their uuid once known,
     * else the lowercase name — migrating any legacy name-keyed friendships
     * and requests to that uuid the moment it becomes resolvable.
     *
     * @param name player name.
     * @return the identity key to use for lookups and storage.
     */
    private fun keyOf(name: String): String {
        val lower = name.lowercase()
        val resolved = resolveUuid(lower) ?: return lower
        migrateIfKnown(lower, resolved)
        displayNames[resolved] = lower
        return resolved
    }

    /**
     * The last-known display name of an identity key: itself for a
     * name-keyed fallback entry, or the tracked name for a uuid key.
     *
     * @param key identity key.
     * @return the name to show for it.
     */
    private fun nameOf(key: String): String = displayNames[key] ?: key

    /**
     * Moves every friendship and request referencing the legacy name key to
     * the now-known uuid key, carrying the data forward unchanged.
     *
     * @param name the lowercase name a legacy entry may reference.
     * @param resolved the now-known uuid.
     */
    private fun migrateIfKnown(name: String, resolved: String) {
        var changed = false
        friendships.filter { name in it }.toList().forEach { pair ->
            friendships.remove(pair)
            friendships += pair.map { if (it == name) resolved else it }.toSet()
            changed = true
        }
        requests.remove(name)?.let { from ->
            requests.getOrPut(resolved) { mutableSetOf() }.addAll(from)
            changed = true
        }
        requests.values.forEach { from ->
            if (from.remove(name)) {
                from += resolved
                changed = true
            }
        }
        if (changed) {
            persist()
        }
    }

    private fun persist() {
        storage.write(
            DOCUMENT,
            json.encodeToString(
                FriendDocument(
                    friendships = friendships.map { it.toList().sorted() },
                    requests = requests.mapValues { it.value.toSet() },
                    displayNames = displayNames.toMap(),
                ),
            ),
        )
    }

    private companion object {
        /** Document key holding the friendship state. */
        const val DOCUMENT = "friends"

        /** Default cooldown between requests from the same sender to the same target. */
        const val DEFAULT_REQUEST_COOLDOWN_MILLIS = 60_000L
    }
}
