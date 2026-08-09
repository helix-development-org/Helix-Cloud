package org.helix.node.identity

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Persisted uuid to last-known-lowercase-name mapping, the node-level source
 * of truth identity-sensitive addons (bans, permissions, warns, friends) key
 * their data on instead of the spoofable player name.
 *
 * Updated on every player join. A name always resolves to whoever most
 * recently joined under it, so a renamed or Mojang-recycled name never
 * resolves back to a previous owner's uuid — the mechanism that stops name
 * succession from inheriting another player's bans or permissions.
 *
 * @property storage node-scoped document store (owner `identity`).
 */
class IdentityRegistry(private val storage: AddonStorage) {
    // Compact rather than pretty: this document is machine-written on every
    // join and never hand-edited, so whitespace is pure payload/GC overhead.
    private val json = Json { prettyPrint = false }
    private val nameByUuid = linkedMapOf<String, String>()
    private val uuidByName = linkedMapOf<String, String>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, String>>(raw) }
                .getOrDefault(emptyMap())
                .forEach { (uuid, name) ->
                    nameByUuid[uuid] = name
                    uuidByName[name] = uuid
                }
        }
    }

    /**
     * Records a join, updating both directions of the mapping.
     *
     * A no-op when the bridge did not report a uuid (older bridge versions,
     * or an offline-mode server) — the caller keeps operating on the name
     * alone until a uuid is eventually observed.
     *
     * @param name player name as reported at join.
     * @param uuid player uuid, if the bridge reported one.
     */
    @Synchronized
    fun recordJoin(name: String, uuid: String?) {
        if (uuid.isNullOrBlank()) {
            return
        }
        val lower = name.lowercase()
        val previousName = nameByUuid[uuid]
        if (previousName != null && previousName != lower && uuidByName[previousName] == uuid) {
            // the old name no longer belongs to this uuid, so a future rename victim's
            // lookup of that name must not still resolve back to this player.
            uuidByName.remove(previousName)
        }
        nameByUuid[uuid] = lower
        uuidByName[lower] = uuid
        persist()
    }

    /**
     * Resolves a player name to its current owner's uuid.
     *
     * @param name player name, case-insensitive.
     * @return the uuid, or `null` when this node has never seen that name join.
     */
    @Synchronized
    fun resolveUuid(name: String): String? = uuidByName[name.lowercase()]

    /**
     * The last-known name a uuid joined under.
     *
     * @param uuid player uuid.
     * @return the last-known lowercase name, or `null` when unknown.
     */
    @Synchronized
    fun lastKnownName(uuid: String): String? = nameByUuid[uuid]

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(nameByUuid.toMap()))
    }

    private companion object {
        /** Document key holding the uuid to last-known-name map. */
        const val DOCUMENT = "identities"
    }
}
