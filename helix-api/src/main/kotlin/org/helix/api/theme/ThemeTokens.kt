package org.helix.api.theme

/**
 * Colour tokens in MiniMessage texts: `<tc:accent>Gold</tc:accent>` renders in the active theme's `accent` colour.
 *
 * [expand] is a plain text rewrite done BEFORE MiniMessage parsing: `<tc:x>` becomes `<#rrggbb>` and `</tc:x>`
 * (or the short `</tc>`, which closes the most recently opened token) becomes `</#rrggbb>`. It needs no
 * Adventure, so the node, both bridges, Paper plugins and the dashboard (TypeScript port) share the algorithm.
 *
 * Why `tc`: MiniMessage already uses `c` as an alias of `color` (`<c:gold>` is vanilla gold), and tag names
 * cannot contain dots. Literal colours (`<#ff0000>`, `<red>`) stay allowed and are left untouched.
 *
 * Each process holds one installed token map ([install]); [LegacyToMini] expands with it, so every text that
 * goes through it renders themed. Unknown token names fall back to the `text` token.
 */
object ThemeTokens {
    /** Neutral token values of Helix without theme (vanilla-like colours). */
    val NEUTRAL: Map<String, String> = linkedMapOf(
        "text" to "#e8e8e8",
        "accent" to "#ffaa00",
        "muted" to "#888888",
        "subtle" to "#cccccc",
        "disabled" to "#aaaaaa",
        "disabled.light" to "#bbbbbb",
        "primary" to "#5b8cff",
        "success" to "#55ff55",
        "error" to "#ff5555",
        "warn" to "#ffcc33",
        "info" to "#55ccff",
        "rarity.normal" to "#cccccc",
        "rarity.special" to "#55ccff",
        "rarity.elite" to "#cc66ff",
        "dark" to "#202020",
        "screen.ink" to "#404040",
        "screen.faded" to "#6b6b6b",
        "screen.title" to "#404040",
        "screen.light" to "#ffffff",
        "screen.white" to "#ffffff",
        "screen.red" to "#aa0000",
        "screen.green" to "#00aa00",
        "screen.disabled" to "#8b8b8b",
    )

    @Volatile
    private var installed: Map<String, String> = NEUTRAL

    private val TAG = Regex("""<(/?)tc(?::([a-z0-9_.\-]+))?>""")

    /** The token map of this process (neutral until [install] is called). */
    val current: Map<String, String> get() = installed

    /**
     * Installs the token map of the active theme for this process; missing tokens keep their neutral value.
     *
     * @param tokens token name to `#rrggbb`.
     */
    fun install(tokens: Map<String, String>) {
        installed = NEUTRAL + tokens
    }

    /** Restores the neutral token map (tests, theme removal). */
    fun reset() {
        installed = NEUTRAL
    }

    /**
     * Rewrites every colour token tag of a text with the given token map.
     *
     * @param text MiniMessage text.
     * @param tokens token map; defaults to the installed one.
     * @return the text with `<tc:x>`/`</tc:x>`/`</tc>` replaced by hex colour tags; unchanged without token tags.
     */
    fun expand(text: String, tokens: Map<String, String> = installed): String {
        if (!text.contains("tc")) return text
        val open = ArrayDeque<String>()
        return TAG.replace(text) { match ->
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2]
            when {
                !closing && name.isEmpty() -> match.value
                !closing -> resolve(name, tokens).also { open.addLast(it) }.let { "<$it>" }
                name.isEmpty() -> open.removeLastOrNull()?.let { "</$it>" } ?: ""
                else -> {
                    val color = resolve(name, tokens)
                    val index = open.lastIndexOf(color)
                    if (index >= 0) open.removeAt(index)
                    "</$color>"
                }
            }
        }
    }

    private fun resolve(name: String, tokens: Map<String, String>): String =
        tokens[name] ?: tokens["text"] ?: NEUTRAL.getValue("text")
}
