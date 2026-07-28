package de.tytoss.igui.display

import net.kyori.adventure.key.Key

/**
 * Names the resource-pack fonts [de.tytoss.igui.display.DisplayBuilder] relies
 * on: one font providing the invisible positive/negative spacing glyphs
 * ([SpacingRenderer]), and one font per text row providing correctly
 * vertically-positioned text glyphs. Configured via
 * [de.tytoss.igui.IGuiConfiguration.fonts]; the defaults assume a resource
 * pack that defines these fonts under the `minecraft` namespace.
 *
 * @property namespace default namespace used to resolve [font] and build [textRowPrefix].
 * @property spacingFont font key providing the positive/negative-width spacing glyphs.
 * @property textRowPrefix key prefix for per-row text fonts; the row number is appended.
 */
data class GuiFontConfiguration(
    val namespace: String = Key.MINECRAFT_NAMESPACE,
    val spacingFont: Key = Key.key(namespace, "spaces"),
    val textRowPrefix: String = "$namespace:text_row_",
) {

    /**
     * Resolves the font key for a given text row.
     *
     * @param row the text row, must be in `0..6`.
     * @return the font key for that row.
     */
    fun textRow(row: Int): Key {
        require(row in 0..6) { "Text row must be in 0..6" }
        return Key.key("$textRowPrefix$row")
    }

    /**
     * Resolves a texture's font key, namespacing it with [namespace] unless
     * it already contains an explicit namespace.
     *
     * @param value a bare font name, or a full `namespace:font` key.
     * @return the resolved font key.
     */
    fun font(value: String): Key =
        if (':' in value) Key.key(value) else Key.key(namespace, value)
}
