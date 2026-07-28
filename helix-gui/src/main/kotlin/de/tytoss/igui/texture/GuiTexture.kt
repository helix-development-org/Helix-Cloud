package de.tytoss.igui.texture

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor


/**
 * A texture ready to be rendered by [de.tytoss.igui.display.DisplayBuilder]:
 * the compiled, cached counterpart of a [GuiTextureDefinition], produced by
 * `TextureCompiler` and resolved via [de.tytoss.igui.IGui.texture]/[de.tytoss.igui.IGui.cachedTexture].
 *
 * @property id unique texture id.
 * @property character the single-codepoint character rendered as this texture's glyph.
 * @property font the font key the character is rendered with.
 * @property widthPixels rendered width, in pixels.
 * @property heightPixels rendered height, in pixels.
 * @property advancePixels cursor advance after rendering, in pixels.
 * @property clientAnimated whether the underlying resource-pack texture is an animated (`.mcmeta`) texture.
 */
class GuiTexture internal constructor(
    val id: String,
    val character: String,
    val font: Key,
    val widthPixels: Int,
    val heightPixels: Int,
    val advancePixels: Int,
    val clientAnimated: Boolean,
) {
    private val whiteComponent: Component = Component.text(character, NamedTextColor.WHITE).font(font)

    /**
     * Builds the text component that renders this texture's glyph.
     *
     * @param color tint applied to the glyph; white reuses a pre-built component.
     * @return the renderable component.
     */
    fun component(color: TextColor = NamedTextColor.WHITE): Component =
        if (color == NamedTextColor.WHITE) whiteComponent else whiteComponent.color(color)

    companion object {
        /** Largest allowed [widthPixels]/[heightPixels] for any texture. */
        const val MAX_SIZE_PIXELS: Int = 864

        /** Largest allowed magnitude of [advancePixels]. */
        const val MAX_ADVANCE_PIXELS: Int = MAX_SIZE_PIXELS + 1
    }
}
