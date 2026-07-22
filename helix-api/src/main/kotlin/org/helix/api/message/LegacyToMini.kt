package org.helix.api.message

/**
 * Translates legacy `&`/`§` color codes into MiniMessage tags, so every
 * configurable text can freely mix both styles and is rendered uniformly
 * through MiniMessage by the bridges.
 */
object LegacyToMini {
    /** Legacy code → MiniMessage tag. */
    private val TAGS: Map<Char, String> = mapOf(
        '0' to "<black>", '1' to "<dark_blue>", '2' to "<dark_green>", '3' to "<dark_aqua>",
        '4' to "<dark_red>", '5' to "<dark_purple>", '6' to "<gold>", '7' to "<gray>",
        '8' to "<dark_gray>", '9' to "<blue>", 'a' to "<green>", 'b' to "<aqua>",
        'c' to "<red>", 'd' to "<light_purple>", 'e' to "<yellow>", 'f' to "<white>",
        'k' to "<obfuscated>", 'l' to "<bold>", 'm' to "<strikethrough>",
        'n' to "<underlined>", 'o' to "<italic>", 'r' to "<reset>",
    )

    /**
     * Whether the character is a valid legacy formatting code.
     *
     * @param code the candidate code character.
     * @return `true` for `0-9`, `a-f`, `k-o` and `r`.
     */
    fun isLegacyCode(code: Char): Boolean = TAGS.containsKey(code.lowercaseChar())

    /**
     * Rewrites all legacy codes in a text as MiniMessage tags.
     *
     * @param text the raw text.
     * @return the text with `&x`/`§x` replaced by MiniMessage tags.
     */
    fun translate(text: String): String {
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val tag = if ((c == '&' || c == '§') && i + 1 < text.length) {
                TAGS[text[i + 1].lowercaseChar()]
            } else {
                null
            }
            if (tag != null) {
                builder.append(tag)
                i += 2
            } else {
                builder.append(c)
                i++
            }
        }
        return builder.toString()
    }
}
