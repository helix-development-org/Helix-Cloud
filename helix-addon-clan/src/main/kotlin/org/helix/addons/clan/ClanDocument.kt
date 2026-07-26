package org.helix.addons.clan

import kotlinx.serialization.Serializable

/**
 * Rank of a member within a clan, ordered by [rank].
 *
 * @property rank numeric weight; a higher rank outranks a lower one for
 *   moderation actions such as kick, promote and demote.
 */
enum class ClanRole(val rank: Int) {
    /** Founder and highest authority; exactly one per clan. */
    OWNER(3),

    /** Trusted member who may invite, kick members and withdraw funds. */
    OFFICER(2),

    /** Regular member with no management rights. */
    MEMBER(1),
}

/**
 * A single clan with its members, tag and shared bank.
 *
 * @property name unique, human readable clan name.
 * @property tag short uppercase display tag (2-5 alphanumeric characters).
 * @property owner lowercase name of the owning player.
 * @property members map of lowercase member name to their [ClanRole].
 * @property bank shared coin balance held by the clan.
 * @property createdAtEpochMs creation timestamp in epoch milliseconds.
 */
@Serializable
data class Clan(
    val name: String,
    val tag: String,
    val owner: String,
    val members: Map<String, ClanRole> = emptyMap(),
    val bank: Long = 0,
    val createdAtEpochMs: Long = 0,
)

/**
 * Persisted clan state.
 *
 * @property clans map of lowercase clan id to its [Clan].
 * @property members index of lowercase player name to the clan id they
 *   belong to, keeping membership lookups O(1) and a player in at most one
 *   clan.
 */
@Serializable
data class ClanDocument(
    val clans: Map<String, Clan> = emptyMap(),
    val members: Map<String, String> = emptyMap(),
)

/**
 * Flat, panel-friendly view of a clan for JSON admin actions.
 *
 * @property id lowercase clan id.
 * @property name clan name.
 * @property tag clan tag.
 * @property owner lowercase owner name.
 * @property memberCount number of members.
 * @property bank shared bank balance.
 */
@Serializable
data class ClanSummary(
    val id: String,
    val name: String,
    val tag: String,
    val owner: String,
    val memberCount: Int,
    val bank: Long,
)
