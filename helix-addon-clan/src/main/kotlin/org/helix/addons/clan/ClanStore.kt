package org.helix.addons.clan

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Clan persistence backed by the addon's document storage.
 *
 * Keeps the [ClanDocument.clans] table and the [ClanDocument.members]
 * index consistent; every mutating call persists the whole document. All
 * public methods are `@Synchronized` so concurrent command handlers see a
 * consistent view.
 *
 * @property storage addon-scoped document store.
 */
class ClanStore(private val storage: AddonStorage) {
    private val json = Json { prettyPrint = true }
    private val clans = mutableMapOf<String, Clan>()
    private val members = mutableMapOf<String, String>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            val document = json.decodeFromString<ClanDocument>(raw)
            clans.putAll(document.clans)
            members.putAll(document.members)
        }
    }

    /**
     * Looks up a clan by its lowercase id.
     *
     * @param id clan id.
     * @return the clan, or `null` when unknown.
     */
    @Synchronized
    fun clanById(id: String): Clan? = clans[id.lowercase()]

    /**
     * Returns the clan a player belongs to.
     *
     * @param player player name in any case.
     * @return the player's clan, or `null` when clanless.
     */
    @Synchronized
    fun clanOf(player: String): Clan? = members[player.lowercase()]?.let { clans[it] }

    /**
     * Returns the id of the clan a player belongs to.
     *
     * @param player player name in any case.
     * @return the clan id, or `null` when clanless.
     */
    @Synchronized
    fun clanIdOf(player: String): String? = members[player.lowercase()]

    /**
     * Resolves a clan from a user supplied token, matching (in order) its
     * id, tag or name case-insensitively.
     *
     * @param token id, tag or name.
     * @return the matching clan id, or `null` when nothing matches.
     */
    @Synchronized
    fun resolveId(token: String): String? {
        val key = token.lowercase()
        if (clans.containsKey(key)) {
            return key
        }
        return clans.entries.firstOrNull { (_, clan) ->
            clan.tag.equals(token, ignoreCase = true) || clan.name.equals(token, ignoreCase = true)
        }?.key
    }

    /**
     * Whether a tag is already used by any clan.
     *
     * @param tag tag in any case.
     * @return `true` when taken.
     */
    @Synchronized
    fun tagTaken(tag: String): Boolean = clans.values.any { it.tag.equals(tag, ignoreCase = true) }

    /**
     * Whether a name is already used by any clan.
     *
     * @param name name in any case.
     * @return `true` when taken.
     */
    @Synchronized
    fun nameTaken(name: String): Boolean = clans.values.any { it.name.equals(name, ignoreCase = true) }

    /**
     * Creates a clan owned by the given player, who becomes its
     * [ClanRole.OWNER].
     *
     * @param tag clan tag (stored uppercase).
     * @param name unique clan name.
     * @param owner founding player name.
     * @param epochMs creation timestamp.
     * @return the created clan, or `null` when the owner already has a
     *   clan or the tag/name is taken.
     */
    @Synchronized
    fun create(tag: String, name: String, owner: String, epochMs: Long): Clan? {
        val ownerKey = owner.lowercase()
        val id = name.lowercase()
        if (members.containsKey(ownerKey) || tagTaken(tag) || nameTaken(name) || clans.containsKey(id)) {
            return null
        }
        val clan = Clan(
            name = name,
            tag = tag.uppercase(),
            owner = ownerKey,
            members = mapOf(ownerKey to ClanRole.OWNER),
            bank = 0,
            createdAtEpochMs = epochMs,
        )
        clans[id] = clan
        members[ownerKey] = id
        persist()
        return clan
    }

    /**
     * Adds a member to a clan.
     *
     * @param clanId target clan id.
     * @param player player to add.
     * @param role role to grant.
     * @return `false` when the clan is unknown or the player already has a
     *   clan.
     */
    @Synchronized
    fun addMember(clanId: String, player: String, role: ClanRole = ClanRole.MEMBER): Boolean {
        val id = clanId.lowercase()
        val clan = clans[id] ?: return false
        val key = player.lowercase()
        if (members.containsKey(key)) {
            return false
        }
        clans[id] = clan.copy(members = clan.members + (key to role))
        members[key] = id
        persist()
        return true
    }

    /**
     * Removes a member from their clan.
     *
     * @param player player to remove.
     * @return `false` when the player is clanless.
     */
    @Synchronized
    fun removeMember(player: String): Boolean {
        val key = player.lowercase()
        val id = members[key] ?: return false
        val clan = clans[id] ?: return false
        clans[id] = clan.copy(members = clan.members - key)
        members.remove(key)
        persist()
        return true
    }

    /**
     * Sets a member's role.
     *
     * @param clanId clan id.
     * @param player member name.
     * @param role new role.
     * @return `false` when the clan or member is unknown.
     */
    @Synchronized
    fun setRole(clanId: String, player: String, role: ClanRole): Boolean {
        val id = clanId.lowercase()
        val clan = clans[id] ?: return false
        val key = player.lowercase()
        if (!clan.members.containsKey(key)) {
            return false
        }
        clans[id] = clan.copy(members = clan.members + (key to role))
        persist()
        return true
    }

    /**
     * Transfers ownership: the new owner becomes [ClanRole.OWNER] and the
     * former owner is demoted to [ClanRole.OFFICER].
     *
     * @param clanId clan id.
     * @param player new owner, must already be a member.
     * @return `false` when the clan or member is unknown.
     */
    @Synchronized
    fun setOwner(clanId: String, player: String): Boolean {
        val id = clanId.lowercase()
        val clan = clans[id] ?: return false
        val key = player.lowercase()
        if (!clan.members.containsKey(key)) {
            return false
        }
        clans[id] = clan.copy(
            owner = key,
            members = clan.members + (clan.owner to ClanRole.OFFICER) + (key to ClanRole.OWNER),
        )
        persist()
        return true
    }

    /**
     * Changes a clan's tag. The verification is reset — a changed tag is
     * new content and needs fresh admin approval before it is displayed.
     *
     * @param clanId clan id.
     * @param tag new tag (stored uppercase).
     * @return `false` when the clan is unknown or the tag is used by
     *   another clan.
     */
    @Synchronized
    fun setTag(clanId: String, tag: String): Boolean {
        val id = clanId.lowercase()
        val clan = clans[id] ?: return false
        if (clans.any { (otherId, other) -> otherId != id && other.tag.equals(tag, ignoreCase = true) }) {
            return false
        }
        clans[id] = clan.copy(tag = tag.uppercase(), verified = false)
        persist()
        return true
    }

    /**
     * Sets a clan's verification state (admin approval of the tag).
     *
     * @param clanId clan id.
     * @param verified `true` when the tag is approved for display.
     * @return `false` when the clan is unknown.
     */
    @Synchronized
    fun setVerified(clanId: String, verified: Boolean): Boolean {
        val id = clanId.lowercase()
        val clan = clans[id] ?: return false
        clans[id] = clan.copy(verified = verified)
        persist()
        return true
    }

    /**
     * Disbands a clan, removing it and all its member index entries.
     *
     * @param clanId clan id.
     * @return `false` when the clan is unknown.
     */
    @Synchronized
    fun disband(clanId: String): Boolean {
        val id = clanId.lowercase()
        val clan = clans.remove(id) ?: return false
        clan.members.keys.forEach { members.remove(it) }
        persist()
        return true
    }

    /**
     * Adjusts a clan's bank balance by a signed delta.
     *
     * @param clanId clan id.
     * @param delta amount to add (may be negative).
     * @return `false` when the clan is unknown or the balance would drop
     *   below zero.
     */
    @Synchronized
    fun adjustBank(clanId: String, delta: Long): Boolean {
        val id = clanId.lowercase()
        val clan = clans[id] ?: return false
        val updated = clan.bank + delta
        if (updated < 0) {
            return false
        }
        clans[id] = clan.copy(bank = updated)
        persist()
        return true
    }

    /**
     * Lists all clans ordered by descending member count.
     *
     * @return clan id to clan pairs, largest clan first.
     */
    @Synchronized
    fun allClans(): List<Pair<String, Clan>> =
        clans.entries.sortedByDescending { it.value.members.size }.map { it.key to it.value }

    private fun persist() {
        storage.write(
            DOCUMENT,
            json.encodeToString(ClanDocument(clans = clans.toMap(), members = members.toMap())),
        )
    }

    private companion object {
        /** Document key holding the clan state. */
        const val DOCUMENT = "clans"
    }
}
