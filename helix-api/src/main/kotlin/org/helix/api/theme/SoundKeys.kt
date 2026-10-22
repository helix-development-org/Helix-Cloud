package org.helix.api.theme

/**
 * Semantic sound keys of the platform UI. Code plays a semantic key (`ui.click`); the active theme maps it to a
 * sound ([ThemeView.sounds]); without theme the vanilla sounds of [VANILLA] play.
 *
 * Keys that contain a colon are sound event ids already (`minecraft:…`, `mymod:…`) and play unchanged — this
 * keeps plugins that were compiled against concrete sound ids working.
 */
object SoundKeys {
    /** Button click. */
    const val UI_CLICK: String = "ui.click"

    /** Page turn (paged menus). */
    const val UI_PAGE: String = "ui.page"

    /** Opening a menu or scroll. */
    const val UI_RUSTLE: String = "ui.rustle"

    /** Confirmation and rewards. */
    const val UI_STAMP: String = "ui.stamp"

    /** Purchases and money. */
    const val UI_COINS: String = "ui.coins"

    /** Errors. */
    const val UI_ERROR: String = "ui.error"

    /** All UI keys. */
    val UI: List<String> = listOf(UI_CLICK, UI_PAGE, UI_RUSTLE, UI_STAMP, UI_COINS, UI_ERROR)

    /** Vanilla fallbacks of the UI keys, used without theme. */
    val VANILLA: Map<String, ThemeSound> = linkedMapOf(
        UI_CLICK to ThemeSound("minecraft:ui.button.click", "master", 0.6f),
        UI_PAGE to ThemeSound("minecraft:item.book.page_turn", "master"),
        UI_RUSTLE to ThemeSound("minecraft:item.book.put", "master"),
        UI_STAMP to ThemeSound("minecraft:block.wood.place", "master"),
        UI_COINS to ThemeSound("minecraft:entity.experience_orb.pickup", "master", 0.7f),
        UI_ERROR to ThemeSound("minecraft:block.note_block.bass", "master"),
    )

    /**
     * Resolves a key to the sound that plays.
     *
     * @param key semantic key or sound event id.
     * @param sounds the theme's sound map ([ThemeView.sounds]).
     * @return the sound; a key with a colon plays as-is; an unknown semantic key resolves to `null`.
     */
    fun resolve(key: String, sounds: Map<String, ThemeSound>): ThemeSound? = when {
        key.contains(':') -> ThemeSound(key)
        else -> sounds[key] ?: VANILLA[key]
    }
}
