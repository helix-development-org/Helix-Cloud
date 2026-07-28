package de.tytoss.igui.texture

import net.kyori.adventure.key.Key

/**
 * The stored, serializable description of a texture: which resource-pack
 * font/character glyph renders it and how big it is, as loaded from or
 * written to a [de.tytoss.igui.database.GuiTextureDatabase]. Compiled into a
 * cacheable, renderable [GuiTexture] by `TextureCompiler`.
 *
 * @property id unique texture id.
 * @property character the single-codepoint character the resource pack maps to this texture's glyph.
 * @property font the font key the character must be rendered with to show this texture.
 * @property widthPixels rendered width, in pixels; must be in `1..`[GuiTexture.MAX_SIZE_PIXELS].
 * @property heightPixels rendered height, in pixels; must be in `1..`[GuiTexture.MAX_SIZE_PIXELS].
 * @property advancePixels cursor advance after rendering, in pixels; may be negative to overlap
 *  the next element, and defaults to one more than [widthPixels].
 * @property clientAnimated whether the underlying resource-pack texture is an animated (`.mcmeta`) texture.
 */
data class GuiTextureDefinition(
    val id: String,
    val character: String,
    val font: Key,
    val widthPixels: Int,
    val heightPixels: Int = 18,
    val advancePixels: Int = widthPixels + 1,
    val clientAnimated: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Texture id must not be blank" }
        require(character.codePointCount(0, character.length) == 1) {
            "Texture '$id' character must contain exactly one Unicode code point"
        }
        require(widthPixels in 1..GuiTexture.MAX_SIZE_PIXELS) {
            "Texture '$id' width must be in 1..${GuiTexture.MAX_SIZE_PIXELS} pixels"
        }
        require(heightPixels in 1..GuiTexture.MAX_SIZE_PIXELS) {
            "Texture '$id' height must be in 1..${GuiTexture.MAX_SIZE_PIXELS} pixels"
        }
        require(advancePixels in -GuiTexture.MAX_ADVANCE_PIXELS..GuiTexture.MAX_ADVANCE_PIXELS) {
            "Texture '$id' advance must be between " +
                    "-${GuiTexture.MAX_ADVANCE_PIXELS} and ${GuiTexture.MAX_ADVANCE_PIXELS}"
        }
    }
}
