package de.tytoss.igui.sound

import de.tytoss.igui.screen.Villagers

/**
 * Sound event keys of the network's own sound pack (design concept §16 "Eigenes Sound-Pack").
 *
 * The audio is synthesised by `tools/sounds/synth.mjs`, lives in `helix-addon-guis-paper/src/pack/resources/sounds`
 * and is written into the shared GUI pack (`assets/reich/sounds.json`) by the guis pack generator, which also
 * checks that every generated event is listed here. Pure strings, so this works without Paper or Adventure:
 * play them with `Key.key(NetworkSounds.UI_CLICK)` and the source from [category].
 *
 * Events with several variants (every UI sound has three, villager babble 20–40 syllables) are picked randomly
 * by the client on each play.
 */
object NetworkSounds {
    /** Resource-pack namespace of all network sounds (the network name is still open; §21). */
    const val NAMESPACE = "reich"

    /** Dry wooden click — every button (§16 "Sound Klick"). */
    const val UI_CLICK = "$NAMESPACE:ui.click"

    /** Turning a ledger page. */
    const val UI_PAGE = "$NAMESPACE:ui.page"

    /** Parchment rustle — opening a scroll or parchment menu. */
    const val UI_RUSTLE = "$NAMESPACE:ui.rustle"

    /** Wax seal being pressed — confirmations and rewards. */
    const val UI_STAMP = "$NAMESPACE:ui.stamp"

    /** Coins — purchases and rewards (§16 "Sound Erfolg"). */
    const val UI_COINS = "$NAMESPACE:ui.coins"

    /** Dull wooden thud — errors (§16 "Sound Fehler"; pair it with the speaking villager's [hmm]). */
    const val UI_ERROR = "$NAMESPACE:ui.error"

    /** MineClash: a card is played (short "plop"). */
    const val MINECLASH_CARD_PLAY = "$NAMESPACE:mineclash.card_play"

    /** MineClash: a tower takes a hit. */
    const val MINECLASH_TOWER_HIT = "$NAMESPACE:mineclash.tower_hit"

    /** MineClash: a tower collapses (about 1.8 s). */
    const val MINECLASH_TOWER_FALL = "$NAMESPACE:mineclash.tower_fall"

    /** MineClash: a chest has finished unlocking (little bell). */
    const val MINECLASH_CHEST_READY = "$NAMESPACE:mineclash.chest_ready"

    /** MineClash: a chest is opened (creak, latch, sparkle). */
    const val MINECLASH_CHEST_OPEN = "$NAMESPACE:mineclash.chest_open"

    /** MineClash: short victory fanfare (§16: fanfare only for victory and boss chest). */
    const val MINECLASH_VICTORY = "$NAMESPACE:mineclash.victory"

    /** MineClash: short defeat trombone. */
    const val MINECLASH_DEFEAT = "$NAMESPACE:mineclash.defeat"

    /** MineClash: one tick of the energy countdown. */
    const val MINECLASH_ENERGY_TICK = "$NAMESPACE:mineclash.energy_tick"

    /** All UI sound events. */
    val UI: List<String> = listOf(UI_CLICK, UI_PAGE, UI_RUSTLE, UI_STAMP, UI_COINS, UI_ERROR)

    /** All MineClash sound events. */
    val MINECLASH: List<String> = listOf(
        MINECLASH_CARD_PLAY, MINECLASH_TOWER_HIT, MINECLASH_TOWER_FALL, MINECLASH_CHEST_READY,
        MINECLASH_CHEST_OPEN, MINECLASH_VICTORY, MINECLASH_DEFEAT, MINECLASH_ENERGY_TICK,
    )

    /** Hearing range of villager voices in blocks (§16 "Räumlich"); written as `attenuation_distance`. */
    const val VOICE_RANGE = 12

    /**
     * Babble syllable event of a villager (stage 1 "Plapper-Stimme"): play one per letter while a line is
     * shown, with a slightly varied pitch (e.g. 0.9–1.1); the client picks a random syllable each time.
     * Unknown ids fall back to the narrator ([Villagers.NARRATOR]).
     *
     * @param villagerId id from [Villagers.IDS], e.g. `"bertold"`.
     * @return the event key, e.g. `reich:villager.bertold.babble`.
     */
    fun babble(villagerId: String): String = "$NAMESPACE:villager.${voice(villagerId)}.babble"

    /**
     * The short "Hmm"/"Hrm" every villager line starts with, in the villager's pitch (§16 "Villager-Laut").
     * Unknown ids fall back to the narrator.
     *
     * @param villagerId id from [Villagers.IDS].
     * @return the event key, e.g. `reich:villager.gorm.hmm`.
     */
    fun hmm(villagerId: String): String = "$NAMESPACE:villager.${voice(villagerId)}.hmm"

    /** Every villager voice event (babble and hmm for each id in [Villagers.IDS]). */
    val VILLAGER: List<String> = Villagers.IDS.flatMap { listOf(babble(it), hmm(it)) }

    /** Every sound event of the pack. */
    val ALL: List<String> = UI + MINECLASH + VILLAGER

    /**
     * Minecraft sound category (`master`, `neutral`, `voice`) for an event, so players can control the volume
     * (§16 "Kategorien"). The client ignores the category in `sounds.json`; pass it as the sound source when
     * playing (e.g. `Sound.Source.VOICE` for villagers).
     *
     * @param key a key from this object.
     * @return `voice` for villager voices, `master` for UI sounds and the victory/defeat jingles, `neutral`
     *   for the other in-world MineClash sounds.
     */
    fun category(key: String): String = when {
        key.startsWith("$NAMESPACE:villager.") -> "voice"
        key in UI || key == MINECLASH_VICTORY || key == MINECLASH_DEFEAT -> "master"
        else -> "neutral"
    }

    private fun voice(villagerId: String): String = if (villagerId in Villagers.IDS) villagerId else Villagers.NARRATOR
}
