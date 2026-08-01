package org.helix.node.privacy

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * One recorded address hash of a player.
 *
 * @property hash salted SHA-256 of the join address, hex encoded.
 * @property lastSeenEpochMs last join with this address.
 */
@Serializable
data class AddressHashEntry(val hash: String, val lastSeenEpochMs: Long)

/**
 * Node-wide registry of salted join-address hashes, the data basis of the
 * staff alt-account lookup.
 *
 * Privacy properties, in order: the raw address is hashed immediately on
 * record and never persisted; the salt is a random installation secret
 * generated once and stored alongside (without it the hashes are useless
 * for offline dictionary attacks); per player only the newest
 * [maxPerPlayer] distinct hashes are kept and every entry expires after
 * [retentionDays]. Export/delete for GDPR requests are provided.
 *
 * @property storage node-scoped document store persisting salt and hashes.
 * @property maxPerPlayer distinct hashes kept per player.
 * @property retentionDays days after which an unseen hash expires.
 * @property now clock, injectable for tests.
 */
class AddressHashRegistry(
    private val storage: AddonStorage,
    private val maxPerPlayer: Int = 5,
    private val retentionDays: Long = 90,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val entries = linkedMapOf<String, MutableList<AddressHashEntry>>()
    private val salt: String

    init {
        salt = storage.read(SALT_DOCUMENT) ?: generateSalt().also { storage.write(SALT_DOCUMENT, it) }
        storage.read(DOCUMENT)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, List<AddressHashEntry>>>(raw) }
                .getOrNull()
                ?.forEach { (uuid, hashes) -> entries[uuid] = hashes.toMutableList() }
        }
    }

    /**
     * Records a join address for a player: the address is salted, hashed
     * and discarded; the hash list is pruned to the newest [maxPerPlayer]
     * unexpired entries.
     *
     * @param uuid the joining player's uuid.
     * @param address the raw join address; blank is ignored.
     */
    @Synchronized
    fun record(uuid: String, address: String) {
        if (address.isBlank()) {
            return
        }
        val hash = hash(address)
        val list = entries.getOrPut(uuid) { mutableListOf() }
        list.removeIf { it.hash == hash || expired(it) }
        list.add(0, AddressHashEntry(hash, now()))
        while (list.size > maxPerPlayer) {
            list.removeAt(list.size - 1)
        }
        persist()
    }

    /**
     * The uuids of other players sharing at least one unexpired address
     * hash with the given player.
     *
     * @param uuid the player uuid to look up.
     * @return the sharing uuids, sorted; empty when nothing matches.
     */
    @Synchronized
    fun sharing(uuid: String): List<String> {
        val own = entries[uuid].orEmpty().filterNot(::expired).map { it.hash }.toSet()
        if (own.isEmpty()) {
            return emptyList()
        }
        return entries
            .filterKeys { it != uuid }
            .filterValues { hashes -> hashes.any { !expired(it) && it.hash in own } }
            .keys
            .sorted()
    }

    /**
     * Exports a player's stored hashes for a GDPR request.
     *
     * @param uuid the player uuid.
     * @return the entries as JSON, or `null` when nothing is stored.
     */
    @Synchronized
    fun export(uuid: String): String? =
        entries[uuid]?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it.toList()) }

    /**
     * Deletes a player's stored hashes for a GDPR request.
     *
     * @param uuid the player uuid.
     * @return `true` when entries existed.
     */
    @Synchronized
    fun delete(uuid: String): Boolean {
        val removed = entries.remove(uuid) != null
        if (removed) {
            persist()
        }
        return removed
    }

    private fun hash(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        digest.update(address.trim().lowercase().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expired(entry: AddressHashEntry): Boolean =
        now() - entry.lastSeenEpochMs > retentionDays * DAY_MS

    private fun persist() {
        entries.values.forEach { it.removeIf(::expired) }
        entries.entries.removeIf { it.value.isEmpty() }
        storage.write(DOCUMENT, json.encodeToString(entries.mapValues { it.value.toList() }))
    }

    private companion object {
        /** Storage document holding the per-player hash lists. */
        const val DOCUMENT = "addresses"

        /** Storage document holding the installation salt. */
        const val SALT_DOCUMENT = "salt"

        const val DAY_MS = 86_400_000L

        /** Generates the random installation salt. */
        fun generateSalt(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
