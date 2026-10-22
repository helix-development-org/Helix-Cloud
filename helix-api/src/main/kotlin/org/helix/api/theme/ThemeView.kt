package org.helix.api.theme

/**
 * Read access to the active theme (`AddonContext.theme()` on the node, [ThemeSnapshot.view] on bridges).
 *
 * Every value is already resolved over the neutral defaults: [tokens] always contains every neutral token,
 * [ranks] is never empty, [placeholders] always has `wordmark`, `wordmark_large`, `crown` and `currency_icon`.
 */
interface ThemeView {
    /** Theme id; `none` for the neutral default. */
    val id: String

    /** Display name. */
    val name: String

    /** Content hash; `neutral` for the neutral default. */
    val hash: String

    /** The underlying manifest. */
    val manifest: ThemeManifest

    /** Colour tokens (neutral defaults overlaid by the theme). */
    val tokens: Map<String, String>

    /** Ranks, highest weight first. */
    val ranks: List<ThemeRank>

    /** Global placeholder values. */
    val placeholders: Map<String, String>

    /** Semantic sound key to sound (theme entries over [SoundKeys.VANILLA]). */
    val sounds: Map<String, ThemeSound>

    /** The villager cast (empty without theme). */
    val cast: ThemeCast get() = manifest.cast

    /** The navigator (no places without theme). */
    val navigator: ThemeNavigator get() = manifest.navigator

    /** The cosmetics catalogue (empty without theme). */
    val cosmetics: List<ThemeCosmetic> get() = manifest.cosmetics

    /**
     * Content file of the theme (`content/<path>`), for example `motd/favicon.png`.
     *
     * @param path path below `content/`.
     * @return the bytes, or `null` when the theme has no such file (always `null` on bridges).
     */
    fun content(path: String): ByteArray?

    /**
     * Colour of a token.
     *
     * @param name token name.
     * @return `#rrggbb`; the `text` token for unknown names.
     */
    fun token(name: String): String = tokens[name] ?: tokens.getValue("text")

    /**
     * Replaces badge glyphs in a MiniMessage text by the plain rank labels, for places without the resource pack
     * (Discord, web panel, logs, kick screens).
     *
     * @param text MiniMessage text that may contain badge glyphs.
     * @return the text with `[Owner]`-style labels instead of glyphs.
     */
    fun plain(text: String): String {
        val badges = ranks.mapNotNull { rank -> rank.badge?.let { it to rank.label } }
        if (badges.isEmpty()) return text
        var result = text
        badges.groupBy({ it.first.font }, { it.first.glyph to it.second }).forEach { (font, glyphs) ->
            val run = Regex("""(?:<white>)?<font:${Regex.escape(font)}>(.*?)</font>(?:</white>)?""")
            result = run.replace(result) { match ->
                var inner = match.groupValues[1]
                glyphs.forEach { (glyph, label) -> inner = inner.replace(glyph, label) }
                inner
            }
        }
        return result
    }

    /** Factories and the neutral default. */
    companion object {
        /** Neutral ranks of a Helix install without theme: coloured text labels, no badges. */
        val NEUTRAL_RANKS: List<ThemeRank> get() = NeutralTheme.RANKS

        /** Neutral global placeholders: the wordmark is the bold network name, no icon glyphs. */
        val NEUTRAL_PLACEHOLDERS: Map<String, String> get() = NeutralTheme.PLACEHOLDERS

        /** Manifest of the neutral default. */
        val NEUTRAL_MANIFEST: ThemeManifest get() = NeutralTheme.MANIFEST

        /** The neutral default view: no theme installed or `[theme] id = "none"`. */
        val NEUTRAL: ThemeView by lazy { of(NeutralTheme.MANIFEST, "neutral") }

        /**
         * A view on a manifest, resolving every section over the neutral defaults.
         *
         * @param manifest the theme manifest.
         * @param hash content hash of the theme.
         * @param content loader for `content/` files (node only).
         * @return the view.
         */
        fun of(manifest: ThemeManifest, hash: String, content: (String) -> ByteArray? = { null }): ThemeView =
            ManifestThemeView(manifest, hash, content)
    }
}

/** Neutral values of a Helix install without theme. */
object NeutralTheme {
    /** Coloured text labels, no badges. */
    val RANKS: List<ThemeRank> = listOf(
        ThemeRank("owner", "[Owner]", "#ff5555", 100),
        ThemeRank("admin", "[Admin]", "#ff5555", 80),
        ThemeRank("mod", "[Mod]", "#ffaa00", 70),
        ThemeRank("default", "[Player]", "#aaaaaa", 0, default = true),
    )

    /** The wordmark is the bold network name; no icon glyphs. */
    val PLACEHOLDERS: Map<String, String> = linkedMapOf(
        "wordmark" to "<tc:accent><b>{network}</b></tc:accent>",
        "wordmark_large" to "<tc:accent><b>{network}</b></tc:accent>",
        "crown" to "",
        "currency_icon" to "",
    )

    /** Manifest of the neutral default. */
    val MANIFEST: ThemeManifest = ThemeManifest(id = "none", name = "Helix")
}

/** [ThemeView] over a [ThemeManifest]. */
private class ManifestThemeView(
    override val manifest: ThemeManifest,
    override val hash: String,
    private val loader: (String) -> ByteArray?,
) : ThemeView {
    override val id: String get() = manifest.id
    override val name: String get() = manifest.name
    override val tokens: Map<String, String> = ThemeTokens.NEUTRAL + manifest.tokens
    override val ranks: List<ThemeRank> =
        (manifest.ranks.ifEmpty { NeutralTheme.RANKS }).sortedByDescending { it.weight }
    override val placeholders: Map<String, String> = NeutralTheme.PLACEHOLDERS + manifest.placeholders
    override val sounds: Map<String, ThemeSound> = SoundKeys.VANILLA + manifest.sounds

    override fun content(path: String): ByteArray? = loader(path)
}
