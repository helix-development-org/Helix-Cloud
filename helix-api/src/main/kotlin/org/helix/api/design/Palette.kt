package org.helix.api.design

/**
 * One colour token of the network design concept (docs/design/DESIGN.md §3): three steps, used identically in the
 * resource pack, MiniMessage, the web panel and LabyMod.
 *
 * @property id token id (`gold`, `rarity.elite` …).
 * @property light highlight step (top edge).
 * @property base surface step.
 * @property dark shade step (bottom edge, shadow).
 */
data class ColorToken(val id: String, val light: Int, val base: Int, val dark: Int) {
    /** MiniMessage hex tag of the base step, e.g. `<#ffcf4a>`. */
    val mm: String get() = "<#%06x>".format(base)

    /** Closing MiniMessage tag of [mm]. */
    val mmClose: String get() = "</#%06x>".format(base)

    /**
     * Wraps [text] in the base colour.
     *
     * @param text MiniMessage text.
     * @return the coloured text.
     */
    fun wrap(text: String): String = "$mm$text$mmClose"
}

/** Generated from docs/design/tokens.json — every colour of the network design concept. Add colours there first. */
object Palette {
    /** Holz: Rahmen, Menühintergrund, Dunkelmodus. */
    val wood = ColorToken("wood", 0x7A4A24, 0x5A3418, 0x3B2617)

    /** Pergament: Inhaltsflächen, Infozettel, Hellmodus. */
    val parchment = ColorToken("parchment", 0xFFFAF0, 0xF1E2C2, 0xD8C08E)

    /** Tinte: Kontur, Text auf Pergament, Schatten. */
    val ink = ColorToken("ink", 0x4A2E1C, 0x2A160A, 0x1A0E06)

    /** Eisen: Beschläge, Ecken, Scharniere. */
    val iron = ColorToken("iron", 0xD6DAE0, 0xA8ADB6, 0x6E737C)

    /** Tischdecke: Spielflächen (Kartentisch, Arenatafel). */
    val cloth = ColorToken("cloth", 0xC0302A, 0x8E2A1A, 0x5A160E)

    /** Band-Rot: Titelband, Gefahr, Benachrichtigungs-Badge. */
    val ribbon = ColorToken("ribbon", 0xFF7A6A, 0xD8382E, 0x9A2018)

    /** Gold: Auswahl, aktiv, Belohnung, Rang-Prefix. */
    val gold = ColorToken("gold", 0xFFF0A8, 0xFFCF4A, 0xE0A420)

    /** Smaragd: Bestätigen, Erfolg, Smaragd-Währung. */
    val emerald = ColorToken("emerald", 0x8CF09A, 0x4AC85E, 0x2A8A3E)

    /** Diamant: Premium, Info, Diamant-Währung. */
    val diamond = ColorToken("diamond", 0xA8DCFF, 0x5AB4FF, 0x2F7AD0)

    /** Rose: Rosen-Währung, Rang/Trophäen. */
    val rose = ColorToken("rose", 0xFFB0C0, 0xFF6A8A, 0xC8385A)

    /** Info: Hinweise, Tipps. */
    val info = ColorToken("info", 0xA8DCFF, 0x5AB4FF, 0x2F7AD0)

    /** Erfolg: Erfolgreiche Aktion. */
    val success = ColorToken("success", 0x8CF09A, 0x4AC85E, 0x2A8A3E)

    /** Warnung: Achtung, Bestätigung nötig. */
    val warn = ColorToken("warn", 0xFFE08A, 0xFFB43A, 0xC87A10)

    /** Fehler: Fehler, Verbote. */
    val error = ColorToken("error", 0xFF9A8A, 0xFF5A4A, 0xB8261A)

    /** Gesperrt: Inaktive Knöpfe, gesperrte Inhalte. */
    val disabled = ColorToken("disabled", 0xC8BCA8, 0xA89A84, 0x6E6252)

    /** Normal: Karten, Truhen, Rahmen. */
    val rarityNormal = ColorToken("rarity.normal", 0xE2E6EC, 0xB8C0CC, 0x7A8494)

    /** Besonders: „Besonders“. */
    val raritySpecial = ColorToken("rarity.special", 0xA8DCFF, 0x5AB4FF, 0x2F7AD0)

    /** Elite: ersetzt „Episch“. */
    val rarityElite = ColorToken("rarity.elite", 0xD2A8FF, 0xA05AE8, 0x6A2AB0)

    /** Titan: Gold mit Ketten-Motiv. */
    val rarityTitan = ColorToken("rarity.titan", 0xFFF0A8, 0xFFCF4A, 0xE0A420)

    /** Boss: Rot mit Glüh-Animation. */
    val rarityBoss = ColorToken("rarity.boss", 0xFF9A8A, 0xE8403A, 0x9A2018)

    /** Team Blau: eigene Seite, Türme, Banner. */
    val teamBlue = ColorToken("team.blue", 0x8AB8FF, 0x3A6AE0, 0x1C3A8A)

    /** Team Rot: Gegnerseite, Türme, Banner. */
    val teamRed = ColorToken("team.red", 0xFF8A7A, 0xD8382E, 0x7A1A12)

    /** All tokens by id. */
    val ALL: Map<String, ColorToken> = listOf(wood, parchment, ink, iron, cloth, ribbon, gold, emerald, diamond, rose, info, success, warn, error, disabled, rarityNormal, raritySpecial, rarityElite, rarityTitan, rarityBoss, teamBlue, teamRed).associateBy { it.id }
}

/**
 * A staff rank of the design concept (§9).
 *
 * @property tag chat tag including brackets.
 * @property color rank colour (RGB).
 * @property weight sort weight (higher = more important).
 */
data class StaffRank(val tag: String, val color: Int, val weight: Int)

/** The staff ranks of the design concept, highest first. */
object StaffRanks {
    /** Ranks in display order. */
    val ALL: List<StaffRank> = listOf(
        StaffRank("[Owner]", 0xD8382E, 100),
        StaffRank("[Manager]", 0xFF6A8A, 95),
        StaffRank("[Developer]", 0xA05AE8, 90),
        StaffRank("[Admin]", 0xFF5A4A, 80),
        StaffRank("[Mod]", 0xFFB43A, 70),
        StaffRank("[Support]", 0x4AC85E, 60),
        StaffRank("[Spieler]", 0xC8BCA8, 0),
    )
}
