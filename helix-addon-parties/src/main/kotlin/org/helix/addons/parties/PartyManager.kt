package org.helix.addons.parties

import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory party state.
 *
 * Parties are intentionally ephemeral: unlike the clan system, they hold no
 * [org.helix.api.storage.AddonStorage]-backed identity (no bank, no tag) and
 * are not meant to survive a full node restart — just a leader, a member
 * list and pending invites, cheap enough to rebuild every time players
 * queue up again. A player belongs to at most one party at a time. Only the
 * leader may invite or kick; any member (including the leader) may leave,
 * and leadership transfers to the longest-standing remaining member when
 * the leader leaves.
 */
class PartyManager {
    private val parties = mutableMapOf<String, MutablePartyState>()
    private val membership = mutableMapOf<String, String>()
    private val invites = mutableMapOf<String, MutableSet<String>>()
    private val nextId = AtomicLong(1)

    /**
     * Returns the party a player belongs to.
     *
     * @param player player name, any case.
     * @return the party, or `null` when the player is not in one.
     */
    @Synchronized
    fun partyOf(player: String): Party? = membership[player.lowercase()]?.let { snapshot(it) }

    /**
     * Creates a new party led by the given player.
     *
     * @param leader founding player.
     * @return the created party, or `null` when the player already belongs
     *   to a party.
     */
    @Synchronized
    fun create(leader: String): Party? {
        val key = leader.lowercase()
        if (membership.containsKey(key)) {
            return null
        }
        val id = "party-${nextId.getAndIncrement()}"
        parties[id] = MutablePartyState(leader = key, members = linkedSetOf(key))
        membership[key] = id
        return snapshot(id)
    }

    /**
     * Invites a player to the leader's party.
     *
     * @param leader inviting player, must lead a party.
     * @param target invited player.
     * @return `false` when the leader has no party, isn't its leader, the
     *   target already belongs to a party, or an invite is already pending.
     */
    @Synchronized
    fun invite(leader: String, target: String): Boolean {
        val id = membership[leader.lowercase()] ?: return false
        val party = parties.getValue(id)
        if (party.leader != leader.lowercase() || membership.containsKey(target.lowercase())) {
            return false
        }
        return invites.getOrPut(id) { mutableSetOf() }.add(target.lowercase())
    }

    /**
     * Accepts a pending invite, joining the inviting leader's party.
     *
     * @param player accepting player.
     * @param leader leader of the inviting party.
     * @return `false` when no such invite is pending or the player already
     *   belongs to a party.
     */
    @Synchronized
    fun accept(player: String, leader: String): Boolean {
        val key = player.lowercase()
        if (membership.containsKey(key)) {
            return false
        }
        val id = membership[leader.lowercase()] ?: return false
        val pending = invites[id] ?: return false
        if (!pending.remove(key)) {
            return false
        }
        parties.getValue(id).members += key
        membership[key] = id
        return true
    }

    /**
     * Removes a player from their current party.
     *
     * Leadership transfers to the next member (join order) when the leader
     * leaves with others remaining; the party dissolves once it would
     * become empty.
     *
     * @param player leaving player.
     * @return `false` when the player was not in a party.
     */
    @Synchronized
    fun leave(player: String): Boolean {
        val key = player.lowercase()
        val id = membership[key] ?: return false
        val party = parties.getValue(id)
        party.members.remove(key)
        membership.remove(key)
        if (party.members.isEmpty()) {
            parties.remove(id)
            invites.remove(id)
        } else if (party.leader == key) {
            party.leader = party.members.first()
        }
        return true
    }

    /**
     * Removes a target member from the leader's party.
     *
     * @param leader kicking player, must lead the party.
     * @param target member to remove; must not be the leader.
     * @return `false` when the leader doesn't lead a party, the target is
     *   the leader, or the target isn't a member of that party.
     */
    @Synchronized
    fun kick(leader: String, target: String): Boolean {
        val id = membership[leader.lowercase()] ?: return false
        val party = parties.getValue(id)
        if (party.leader != leader.lowercase() || party.leader == target.lowercase()) {
            return false
        }
        if (!party.members.remove(target.lowercase())) {
            return false
        }
        membership.remove(target.lowercase())
        invites[id]?.remove(target.lowercase())
        return true
    }

    /**
     * Lists pending invites for the party a leader leads.
     *
     * @param leader leader of the party.
     * @return invited player names, empty when none are pending or the
     *   player leads no party.
     */
    @Synchronized
    fun pendingInvites(leader: String): Set<String> {
        val id = membership[leader.lowercase()] ?: return emptySet()
        return invites[id]?.toSet() ?: emptySet()
    }

    private fun snapshot(id: String): Party? {
        val state = parties[id] ?: return null
        return Party(id = id, leader = state.leader, members = state.members.toList())
    }

    private class MutablePartyState(var leader: String, val members: LinkedHashSet<String>)
}
