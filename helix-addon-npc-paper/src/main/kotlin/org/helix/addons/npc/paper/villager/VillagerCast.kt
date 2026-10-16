package org.helix.addons.npc.paper.villager

/**
 * One character of the villager cast (design concept §12 "Charaktere").
 *
 * Pure data, no Paper types, so other modules (for example the MineClash gamemode) can reuse names and colours.
 *
 * @property id stable lowercase id; also the model id (`helix_villager:<id>/<part>`) and voice id.
 * @property name display name, for example `Bertold Knubbel`.
 * @property role profession shown under the name, for example `Bürgermeister`.
 * @property robe robe colour as `#rrggbb`; the speaker name of villager speech uses it (§9).
 * @property ticksPerLetter speaking tempo: server ticks per spoken letter (babble syllable).
 * @property lidRest lid y-scale while the eyes are "open" (0.05 = open; Klunk's half-shut lids are modelled).
 */
data class VillagerCharacter(
    val id: String,
    val name: String,
    val role: String,
    val robe: String,
    val ticksPerLetter: Int = 2,
    val lidRest: Float = 0.05f,
)

/**
 * The villagers of the network (design concept §12), keyed by id: the ten of the network cast followed by the ten
 * CityBuild figures (§12 "CityBuild-Figuren"), which only appear on the CityBuild servers.
 */
object VillagerCast {
    /** Parchment colour of spoken text (§3/§9). */
    const val PARCHMENT = "#f1e2c2"

    /** Light wood colour used for the role line of the name tag. */
    const val WOOD_LIGHT = "#c8a46a"

    /** Every character, in the design concept's order. */
    val ALL: List<VillagerCharacter> = listOf(
        VillagerCharacter("bertold", "Bertold Knubbel", "Bürgermeister", "#8e2a1a", ticksPerLetter = 2),
        VillagerCharacter("mira", "Mira Feilsch", "Händlerin", "#3a8a4a", ticksPerLetter = 1),
        VillagerCharacter("gorm", "Gorm Faust", "Kampfmeister", "#5e5e66", ticksPerLetter = 1),
        VillagerCharacter("elsa", "Elsa Blatt", "Kartenmeisterin", "#2f4a7a", ticksPerLetter = 2),
        VillagerCharacter("klunk", "Klunk", "Truhenwächter", "#6b4a2e", ticksPerLetter = 3),
        VillagerCharacter("pitt", "Pitt Stollen", "Minenarbeiter", "#7a5a3a", ticksPerLetter = 1),
        VillagerCharacter("hedda", "Hedda Wall", "Clanwärterin", "#6e1a1a", ticksPerLetter = 2),
        VillagerCharacter("funkel", "Prof. Funkel", "Forscher", "#6a3a9a", ticksPerLetter = 1),
        VillagerCharacter("ambo", "Ambo Hammerl", "Schmied", "#5e5e66", ticksPerLetter = 2),
        VillagerCharacter("knoepfchen", "Knöpfchen", "Lobby-Erzähler", "#d8a83a", ticksPerLetter = 1),
        VillagerCharacter("lotte", "Lotte Laterne", "Stadtführerin", "#5aa8e0", ticksPerLetter = 1),
        VillagerCharacter("gundula", "Gundula Grundmann", "Katasterbeamtin", "#5a6470", ticksPerLetter = 2),
        VillagerCharacter("fritz", "Fritz Feilbiet", "Krämer", "#e07a2a", ticksPerLetter = 1),
        VillagerCharacter("ludwig", "Ludwig Lot", "Auktionator", "#2e6a40", ticksPerLetter = 1),
        VillagerCharacter("henni", "Henni Hacke", "Vorarbeiterin", "#9a4a32", ticksPerLetter = 1),
        VillagerCharacter("waldemar", "Waldemar Moos", "Förster", "#5a7a3a", ticksPerLetter = 3),
        VillagerCharacter("rupert", "Rupert Ranzen", "Quartiermeister", "#a8783a", ticksPerLetter = 2, lidRest = 0.3f),
        VillagerCharacter("paula", "Paula Paket", "Postbotin", "#f0c030", ticksPerLetter = 1),
        VillagerCharacter("brunhilde", "Brunhilde Brecher", "Waffenmeisterin", "#7a7f88", ticksPerLetter = 2),
        VillagerCharacter("greta", "Greta Goldzahn", "Schatzmeisterin", "#6e3a4a", ticksPerLetter = 2),
    )

    /** Characters by id. */
    val BY_ID: Map<String, VillagerCharacter> = ALL.associateBy { it.id }

    /** All ids, in order. */
    val IDS: List<String> = ALL.map { it.id }

    /**
     * Looks a character up.
     *
     * @param id villager id, any case.
     * @return the character, or `null` for an unknown id.
     */
    fun get(id: String): VillagerCharacter? = BY_ID[id.lowercase()]

    /**
     * MiniMessage prefix of a villager line (§9 "Villager-Rede"): the name in robe colour plus colon, no network
     * prefix.
     *
     * @param character the speaker.
     * @return for example `<#3a8a4a>Mira Feilsch:</#3a8a4a> `.
     */
    fun speakerPrefix(character: VillagerCharacter): String =
        "<${character.robe}>${character.name}:</${character.robe}> "
}
