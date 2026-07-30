package org.helix.addons.guis

import kotlinx.serialization.Serializable

/**
 * Wire/storage form of an `IGui` texture definition (`de.tytoss.igui.texture.GuiTextureDefinition`,
 * a `helix-gui` type this addon deliberately does not depend on — Paper-side
 * IGui menus reach this store over the action HTTP contract instead of a
 * shared Kotlin type, the same arm's-length boundary every addon/bridge
 * component crosses).
 *
 * @property id unique texture id.
 * @property character the single-codepoint glyph character.
 * @property font the font key, as its plain string form (`namespace:value`).
 * @property widthPixels rendered width in pixels.
 * @property heightPixels rendered height in pixels.
 * @property advancePixels cursor advance after rendering, in pixels.
 * @property clientAnimated whether the texture is an animated (`.mcmeta`) texture.
 */
@Serializable
data class GuiTextureRecord(
    val id: String,
    val character: String,
    val font: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val advancePixels: Int,
    val clientAnimated: Boolean,
)
