package org.helix.addons.parties

/**
 * A lightweight, network-wide grouping of players below the level of a clan.
 *
 * @property id stable party id, unique for the lifetime of the node process.
 * @property leader current leader, lowercase player name.
 * @property members all members including the leader, lowercase player
 *   names, in join order.
 */
data class Party(
    val id: String,
    val leader: String,
    val members: List<String>,
)
