package org.helix.addons.lobby.paper

import de.tytoss.igui.screen.ScreenSprite

/**
 * Layout of the navigator ("Karte des Reichs", design concept §14) in container pixels (pure; shared with the pack
 * generator so art, glyphs and clicks line up): a hand-drawn parchment map on the 384×256 board with fixed places.
 * Every place covers 2×2 slot cells; its emblem (animated item) stands on a round parchment plaque, the name sits on a
 * banner below and the responsible villager's head in a neighbouring cell.
 */
object NavigatorGlyphs {
    /** Font of all navigator glyphs (`helix_lobby:navigator`). */
    const val FONT = "helix_lobby:navigator"

    /** Board image size and position. */
    const val BG_W = 384
    const val BG_H = 256
    const val BG_X = -104
    const val BG_Y = -17

    /** Board tiles, glyphs U+E000.. */
    const val BG_TILE = 0xE000

    /** Plaque (40×40) per place: glyph `PLAQUE + place`. */
    const val PLAQUE = 0xE010
    const val PLAQUE_SIZE = 40

    /** Name banner (76×13) per place and state: glyph `BAND + place * 3 + state`. */
    const val BAND = 0xE020
    const val BAND_W = 76
    const val BAND_H = 13

    /** Construction fence (16×16) beside a place under maintenance: glyph `FENCE + place`. */
    const val FENCE = 0xE040
    const val FENCE_SIZE = 16

    /** Emblems (32×32 frame strips) as items, one model per emblem and grey variant. */
    const val EMBLEM_SIZE = 32

    /** Emblem ids. */
    val EMBLEMS = listOf("castle", "swords", "banner", "compass", "crown")

    /** Headline on the ribbon. */
    const val TITLE_TOP = 5
    const val CENTER_X = 88

    /** Namespace of the sprites. */
    const val SPRITE_NAMESPACE = "helix_lobby"

    /**
     * A fixed place of the map.
     *
     * @property id place id (`kesseldorf` …).
     * @property name default display name.
     * @property column first slot column of the 2×2 block.
     * @property row first slot row of the 2×2 block.
     * @property headColumn slot column of the villager head.
     * @property headRow slot row of the villager head.
     * @property villager default villager id at this place.
     */
    data class Place(
        val id: String,
        val name: String,
        val column: Int,
        val row: Int,
        val headColumn: Int,
        val headRow: Int,
        val villager: String,
    ) {
        /** Centre x of the 2×2 block. */
        val centerX: Int get() = 25 + column * 18

        /** Centre y of the 2×2 block. */
        val centerY: Int get() = if (row < 6) 35 + row * 18 else 156 + (row - 6) * 18

        /** Top of the name banner. */
        val bandTop: Int get() = centerY + 17
    }

    /** The places, in glyph order. */
    val PLACES = listOf(
        Place("ankerplatz", "Ankerplatz", 3, 4, 5, 4, "knoepfchen"),
        Place("kesseldorf", "Kesseldorf", 0, 1, 2, 1, "bertold"),
        Place("rosenarena", "Rosenarena", 7, 1, 6, 1, "gorm"),
        Place("grenzlande", "Grenzlande", 7, 6, 6, 6, "hedda"),
        Place("nebelland", "Nebelland", 0, 6, 2, 6, "funkel"),
        Place("reserve", "Lichtung", 3, 7, 5, 7, "elsa"),
        // CityBuild; the city villagers have no NPC model yet, so the mayor's head stands in (DESIGN §14)
        Place("kronstadt", "Kronstadt", 3, 1, 5, 1, "gundula"),
    )

    /** The fog place for coming modes. */
    const val FOG_PLACE = "nebelland"

    /** Banner states, in glyph order. */
    val BAND_STATES = listOf("normal", "full", "maintenance")

    /**
     * @param emblem index in [EMBLEMS].
     * @param grey whether the maintenance (grey) variant is meant.
     * @return the emblem sprite; placed in the top-left cell of a place block and shifted onto its plaque.
     */
    fun emblemSprite(emblem: Int, grey: Boolean = false): ScreenSprite {
        val id = EMBLEMS[emblem]
        val suffix = if (grey) "_grey" else ""
        return ScreenSprite(
            model = "navigator/emblem_$id$suffix",
            texture = "navigator/emblem_$id$suffix",
            size = EMBLEM_SIZE,
            column = 0,
            row = 0,
            centerX = 25,
            centerY = 35,
        )
    }
}
