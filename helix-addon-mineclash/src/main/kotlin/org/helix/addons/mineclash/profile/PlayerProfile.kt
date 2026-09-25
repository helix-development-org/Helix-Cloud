package org.helix.addons.mineclash.profile

import kotlinx.serialization.Serializable

/**
 * One chest slot of a player.
 *
 * @property rarity chest rarity name (for example `NORMAL`).
 * @property openingStarted epoch millis when opening started, `-1` when idle.
 */
@Serializable
data class ChestSlot(val rarity: String, val openingStarted: Long = -1)

/**
 * Persistent MineClash profile of one player — wallet, card collection, deck, chests, village
 * progress and tutorial state. This is the exact JSON shape exchanged with the Paper servers.
 *
 * @property uuid player uuid.
 * @property name last known player name.
 * @property wallet currency name to amount (`SMARAGDE`, `DIAMANTEN`, `ROSEN`).
 * @property cards card id to owned copies.
 * @property deck card ids in deck order.
 * @property deckSlots purchased deck slots.
 * @property chestSlots chest slots waiting to be opened.
 * @property wins match wins.
 * @property losses match losses.
 * @property buildings village house index (as string key) to level.
 * @property mineCollectedAt epoch millis of the last mine collection.
 * @property cardLevels card id to level.
 * @property perks perk key to level.
 * @property tutorialStep current tutorial step.
 * @property tutorialStarted whether the tutorial was started.
 * @property updatedAt epoch millis of the last save.
 */
@Serializable
data class PlayerProfile(
    val uuid: String,
    val name: String = "",
    val wallet: Map<String, Long> = emptyMap(),
    val cards: Map<String, Int> = emptyMap(),
    val deck: List<String> = emptyList(),
    val deckSlots: Int = 0,
    val chestSlots: List<ChestSlot> = emptyList(),
    val wins: Int = 0,
    val losses: Int = 0,
    val buildings: Map<String, Int> = emptyMap(),
    val mineCollectedAt: Long = 0,
    val cardLevels: Map<String, Int> = emptyMap(),
    val perks: Map<String, Int> = emptyMap(),
    val tutorialStep: Int = 0,
    val tutorialStarted: Boolean = false,
    val updatedAt: Long = 0,
) {
    /** Roses balance, the ranking currency. */
    val roses: Long get() = wallet["ROSEN"] ?: 0

    /** Companion with factories. */
    companion object {
        /**
         * Builds the profile of a brand-new player.
         *
         * @param uuid player uuid.
         * @param name player name.
         * @param starterDeck card ids of the starter deck; the player owns one copy of each.
         * @param deckSlots deck slots at the start.
         * @return the initial profile.
         */
        fun starter(uuid: String, name: String, starterDeck: List<String>, deckSlots: Int): PlayerProfile =
            PlayerProfile(
                uuid = uuid,
                name = name,
                wallet = mapOf("SMARAGDE" to 0L, "DIAMANTEN" to 0L, "ROSEN" to 0L),
                cards = starterDeck.associateWith { 1 },
                deck = starterDeck.take(deckSlots),
                deckSlots = deckSlots,
                updatedAt = System.currentTimeMillis(),
            )
    }
}

/**
 * Result of one finished match as reported by a game server.
 *
 * @property mode match mode (`1V1`, `2V2`).
 * @property winnerTeam index of the winning team, `-1` for a draw.
 * @property players team index to player uuids.
 * @property rosesDelta player uuid to rose change.
 * @property startedAt epoch millis.
 * @property endedAt epoch millis.
 */
@Serializable
data class MatchRecord(
    val mode: String,
    val winnerTeam: Int,
    val players: List<List<String>>,
    val rosesDelta: Map<String, Int> = emptyMap(),
    val startedAt: Long = 0,
    val endedAt: Long = 0,
)
