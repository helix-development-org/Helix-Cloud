package org.helix.api.design

/**
 * Rank badges of the design concept (§9 „Rang-Abzeichen AC3“): pixel pills in the rank colour with a gold frame,
 * light from the top left and white lettering (Owner additionally carries a small crown). They are glyphs of the
 * shared GUI pack font [FONT], 9 px high on the 9 px text line (ascent 8), so they fit chat, tab list, name tags and
 * scoreboards. Wherever no resource pack is shown (Discord, web panel, logs, kick screens) the plain [Badge.label]
 * is used instead – [plain] turns a badge prefix back into that text.
 */
object RankBadges {
    /** Font of the rank badge glyphs. */
    const val FONT: String = "helix_guis:ranks"

    /**
     * One rank badge.
     *
     * @property key lower-case rank key (also the permission group name of the seeded groups).
     * @property glyph private-use codepoint of the badge in [FONT].
     * @property label plain-text rank tag for places without the resource pack, e.g. `[Owner]`.
     * @property color rank colour (name colour after the badge), `0xRRGGBB`.
     * @property width badge width in pixels (height is always 9).
     */
    data class Badge(val key: String, val glyph: Char, val label: String, val color: Int, val width: Int) {
        /** MiniMessage of the badge glyph alone (white = untinted). */
        val mini: String get() = "<white><font:$FONT>$glyph</font></white>"

        /** Group prefix: the badge plus one separating space (the name follows in [color]). */
        val prefix: String get() = "$mini "

        /** MiniMessage colour tag of the rank colour, e.g. `<#d8382e>`. */
        val colorTag: String get() = "<#%06x>".format(color)
    }

    /** Owner (U+E000, with crown). */
    val OWNER: Badge = Badge("owner", '', "[Owner]", 0xD8382E, 38)

    /** Manager (U+E001). */
    val MANAGER: Badge = Badge("manager", '', "[Manager]", 0xFF6A8A, 38)

    /** Developer (U+E002). */
    val DEVELOPER: Badge = Badge("developer", '', "[Developer]", 0xA05AE8, 44)

    /** Admin (U+E003). */
    val ADMIN: Badge = Badge("admin", '', "[Admin]", 0xFF5A4A, 30)

    /** Moderator (U+E004). */
    val MOD: Badge = Badge("mod", '', "[Mod]", 0xFFB43A, 22)

    /** Supporter (U+E005). */
    val SUPPORT: Badge = Badge("support", '', "[Support]", 0x4AC85E, 36)

    /** Tester of the closed test phase (U+E006). */
    val TESTER: Badge = Badge("tester", '', "[Tester]", 0x5AB4FF, 31)

    /** Former tester, permanent after the release (U+E007). */
    val BETA: Badge = Badge("beta", '', "[Beta]", 0x5AB4FF, 23)

    /** All badges in display order (highest rank first). */
    val ALL: List<Badge> = listOf(OWNER, MANAGER, DEVELOPER, ADMIN, MOD, SUPPORT, TESTER, BETA)

    /** Name colour of players without a rank badge (grey, design §9 „Spieler“). */
    const val PLAYER_COLOR: Int = 0xC8BCA8

    /**
     * Looks up a badge by rank key or plain tag.
     *
     * @param keyOrTag rank key (`owner`) or tag (`[Owner]`), case-insensitive.
     * @return the badge, or `null` for unknown ranks (e.g. the default player rank).
     */
    fun of(keyOrTag: String): Badge? {
        val key = keyOrTag.trim().trim('[', ']').lowercase()
        return ALL.firstOrNull { it.key == key }
    }

    private val FONT_RUN = Regex("""(?:<white>)?<font:${Regex.escape(FONT)}>(.*?)</font>(?:</white>)?""")

    /**
     * Replaces every badge glyph run of a MiniMessage text by the plain rank tags, e.g. for Discord, the web panel
     * or logs where the resource pack is not available. Other text and tags stay untouched.
     *
     * @param text MiniMessage text that may contain badge glyphs.
     * @return the text with `[Owner]`-style tags instead of glyphs.
     */
    fun plain(text: String): String =
        FONT_RUN.replace(text) { match ->
            match.groupValues[1].map { ch -> ALL.firstOrNull { it.glyph == ch }?.label ?: ch.toString() }.joinToString("")
        }
}
