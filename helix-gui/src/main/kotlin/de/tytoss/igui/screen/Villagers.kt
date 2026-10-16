package de.tytoss.igui.screen

/**
 * The villager cast of the design concept (§12) as far as screens need it: ids in a fixed order (glyph and model
 * indices depend on it) and the item model of each 16×16 head icon (pure; shared with the pack generator).
 */
object Villagers {
    /** Villager ids, in glyph order: the network cast, then the CityBuild figures (§12 "CityBuild-Figuren"). */
    val IDS = listOf(
        "bertold", "mira", "gorm", "elsa", "klunk", "pitt", "hedda", "funkel", "ambo", "knoepfchen",
        "lotte", "gundula", "fritz", "ludwig", "henni", "waldemar", "rupert", "paula", "brunhilde", "greta",
    )

    /** The lobby narrator, speaker of empty states unless a menu names another villager. */
    const val NARRATOR = "knoepfchen"

    /** Namespace of the head models and glyphs. */
    const val NAMESPACE = "helix_guis"

    /**
     * @param id villager id.
     * @return the head's item model path below [NAMESPACE].
     */
    fun headModel(id: String): String = "villager/$id"

    /**
     * @param id villager id.
     * @return the index of [id] (glyph offset), or the narrator's for unknown ids.
     */
    fun index(id: String): Int = IDS.indexOf(id).takeIf { it >= 0 } ?: IDS.indexOf(NARRATOR)
}
