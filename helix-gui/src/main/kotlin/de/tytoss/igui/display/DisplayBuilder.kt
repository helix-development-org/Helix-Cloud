package de.tytoss.igui.display

import de.tytoss.igui.internal.SpacingRenderer
import de.tytoss.igui.internal.TextWidthTable
import de.tytoss.igui.texture.GuiTexture
import de.tytoss.igui.texture.IGuiObject
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * Builds a pixel-precise GUI title [Component] using the resource-pack font
 * trick: textures and text are appended as font-scoped glyphs, and the
 * cursor is nudged between them with invisible positive/negative-width
 * "space" glyphs ([SpacingRenderer]) so the client renders them at exact
 * pixel coordinates instead of following normal text flow.
 *
 * The cursor starts at [DEFAULT_ORIGIN_PIXELS] (the left edge of a chest
 * title's usable area) and advances as textures/text are appended; use
 * [moveTo] or [toStart] to reposition it explicitly. Obtain an instance via
 * [de.tytoss.igui.gui.GuiPageBuilder.title]; call [build] once done to get
 * the finished [GuiTitle].
 */
class DisplayBuilder internal constructor(
    private val fonts: GuiFontConfiguration,
) {
    private val component = Component.text()
    private val spacing = SpacingRenderer(fonts.spacingFont)
    private val widths = TextWidthTable()
    private var cursor = DEFAULT_ORIGIN_PIXELS

    /** The cursor's current horizontal position, in pixels from the title's left edge. */
    val cursorPixels: Int get() = cursor

    /**
     * Advances the cursor by [pixels] without rendering anything visible,
     * by appending an invisible spacing glyph of that width. A negative
     * value moves the cursor backwards (see [negativeSpace]).
     *
     * @param pixels signed pixel offset to advance the cursor by.
     */
    fun space(pixels: Int) {
        spacing.append(pixels, component)
        cursor = Math.addExact(cursor, pixels)
    }

    /**
     * Moves the cursor backwards by [pixels], typically to pull the next
     * element left, e.g. to overlap a texture with the previous one.
     *
     * @param pixels the (non-negative) magnitude to move backwards by.
     */
    fun negativeSpace(pixels: Int) {
        require(pixels >= 0) { "Negative-space magnitude must not be negative" }
        space(-pixels)
    }

    /**
     * Moves the cursor to an absolute pixel position, computing the required
     * (possibly negative) [space].
     *
     * @param pixel the absolute target position, in pixels.
     */
    fun moveTo(pixel: Int): Unit = space(pixel - cursor)

    /** Moves the cursor back to pixel `0`, the very left edge of the title. */
    fun toStart(): Unit = moveTo(0)

    /**
     * Advances the internally tracked cursor position by [pixels] without
     * emitting any glyph. Use this to account for pixels the client itself
     * will render (for example the vanilla title padding) that this builder
     * did not draw.
     *
     * @param pixels signed pixel offset to add to the cursor.
     */
    fun adjust(pixels: Int) {
        cursor = Math.addExact(cursor, pixels)
    }

    /**
     * Appends an [IGuiObject]'s bound texture at the current cursor position.
     *
     * @param texture the object whose texture to render.
     * @param color tint applied to the texture's glyph.
     */
    fun texture(texture: IGuiObject, color: TextColor = NamedTextColor.WHITE) {
        texture(texture.texture, color)
    }

    /**
     * Appends a texture's glyph at the current cursor position and advances
     * the cursor by the texture's [GuiTexture.advancePixels].
     *
     * @param texture the texture to render.
     * @param color tint applied to the texture's glyph.
     */
    fun texture(texture: GuiTexture, color: TextColor = NamedTextColor.WHITE) {
        component.append(texture.component(color))
        cursor = Math.addExact(cursor, texture.advancePixels)
    }

    /**
     * Renders an [IGuiObject]'s texture centered around [centerPixel].
     *
     * @param texture the object whose texture to render.
     * @param centerPixel the pixel to center the texture on.
     * @param color tint applied to the texture's glyph.
     */
    fun centeredTexture(
        texture: IGuiObject,
        centerPixel: Int = CONTENT_CENTER_PIXELS,
        color: TextColor = NamedTextColor.WHITE,
    ) {
        moveTo(centerPixel - texture.texture.widthPixels / 2)
        texture(texture, color)
    }

    /**
     * Renders a texture centered around [centerPixel].
     *
     * @param texture the texture to render.
     * @param centerPixel the pixel to center the texture on.
     * @param color tint applied to the texture's glyph.
     */
    fun centeredTexture(
        texture: GuiTexture,
        centerPixel: Int = CONTENT_CENTER_PIXELS,
        color: TextColor = NamedTextColor.WHITE,
    ) {
        moveTo(centerPixel - texture.widthPixels / 2)
        texture(texture, color)
    }

    /**
     * Renders an [IGuiObject]'s texture, then resets the cursor back to the
     * start — useful for background/decoration layers rendered before the
     * elements that share the same starting position.
     *
     * @param texture the object whose texture to render.
     * @param color tint applied to the texture's glyph.
     */
    fun textureAndReset(
        texture: IGuiObject,
        color: TextColor = NamedTextColor.WHITE,
    ) {
        texture(texture, color)
        toStart()
    }

    /**
     * Renders a texture, then resets the cursor back to the start.
     *
     * @param texture the texture to render.
     * @param color tint applied to the texture's glyph.
     */
    fun textureAndReset(
        texture: GuiTexture,
        color: TextColor = NamedTextColor.WHITE,
    ) {
        texture(texture, color)
        toStart()
    }

    /**
     * Appends plain text using one of the built-in per-row fonts, whose
     * glyph widths are tracked by [characterWidth]-registered overrides (and
     * ASCII defaults otherwise) so the cursor advances accurately.
     *
     * @param value the text to render.
     * @param line the text row (0..6) whose font/vertical position to use.
     * @param color color of the text.
     * @param font font to render with; defaults to the row font for [line].
     */
    fun text(
        value: String,
        line: Int,
        color: TextColor = NamedTextColor.DARK_GRAY,
        font: Key = fonts.textRow(line),
    ) {
        component.append(Component.text(value, color).font(font))
        cursor = Math.addExact(cursor, widths.measure(value))
    }

    /**
     * Like [text], but applies MiniMessage-style decorations and keeps the
     * cursor accurate for them: the client renders `bold` by drawing each
     * glyph twice offset by one pixel (so every glyph advances one pixel
     * more), while `italic`, `obfuscated`, `underlined` and `strikethrough`
     * keep the plain advance. Used to preview a formatted MiniMessage line
     * faithfully, one styled run at a time.
     *
     * @param value the text to render.
     * @param line the text row whose default font/vertical position to use;
     *   ignored when [font] is passed explicitly.
     * @param color color of the text.
     * @param bold whether to render bold (widens every glyph by one pixel).
     * @param italic whether to render italic.
     * @param obfuscated whether to render the animated obfuscated effect.
     * @param underlined whether to underline.
     * @param strikethrough whether to strike through.
     * @param font font to render with; defaults to the row font for [line].
     */
    fun styledText(
        value: String,
        line: Int,
        color: TextColor = NamedTextColor.DARK_GRAY,
        bold: Boolean = false,
        italic: Boolean = false,
        obfuscated: Boolean = false,
        underlined: Boolean = false,
        strikethrough: Boolean = false,
        font: Key = fonts.textRow(line),
    ) {
        component.append(
            Component.text(value, color).font(font)
                .decoration(TextDecoration.BOLD, bold)
                .decoration(TextDecoration.ITALIC, italic)
                .decoration(TextDecoration.OBFUSCATED, obfuscated)
                .decoration(TextDecoration.UNDERLINED, underlined)
                .decoration(TextDecoration.STRIKETHROUGH, strikethrough),
        )
        cursor = Math.addExact(cursor, widths.measure(value) + if (bold) value.length else 0)
    }

    /**
     * Renders [text] centered around [centerPixel], then resets the cursor
     * back to the start.
     *
     * @param value the text to render.
     * @param line the text row (0..6) whose font/vertical position to use.
     * @param centerPixel the pixel to center the text on.
     * @param color color of the text.
     * @param font font to render with; defaults to the row font for [line].
     */
    fun centeredText(
        value: String,
        line: Int,
        centerPixel: Int = CONTENT_CENTER_PIXELS,
        color: TextColor = NamedTextColor.DARK_GRAY,
        font: Key = fonts.textRow(line),
    ) {
        moveTo(centerPixel - widths.measure(value) / 2)
        text(value, line, color, font)
        toStart()
    }

    /**
     * Renders [text] right-aligned so it ends at [endPixel], then resets the
     * cursor back to the start.
     *
     * @param value the text to render.
     * @param line the text row (0..6) whose font/vertical position to use.
     * @param endPixel the pixel the text's right edge should end at.
     * @param color color of the text.
     * @param font font to render with; defaults to the row font for [line].
     */
    fun endText(
        value: String,
        line: Int,
        endPixel: Int = CONTENT_END_PIXELS,
        color: TextColor = NamedTextColor.DARK_GRAY,
        font: Key = fonts.textRow(line),
    ) {
        moveTo(endPixel - widths.measure(value))
        text(value, line, color, font)
        toStart()
    }

    /**
     * Registers a per-instance pixel-width override for a single character,
     * used by [text]/[centeredText]/[endText] to measure text accurately
     * when a character's rendered width does not match the built-in ASCII
     * table (see [TextWidthTable]).
     *
     * @param character exactly one Unicode code point.
     * @param pixels the character's rendered width, in pixels.
     */
    fun characterWidth(character: String, pixels: Int): Unit = widths.register(character, pixels)

    /**
     * Finishes building, capturing the assembled component and final cursor
     * position.
     *
     * @return the built title.
     */
    fun build(): GuiTitle = GuiTitle(component.build(), cursor)

    companion object {
        const val DEFAULT_ORIGIN_PIXELS: Int = 8
        const val CONTENT_CENTER_PIXELS: Int = 88
        const val CONTENT_END_PIXELS: Int = 168
    }
}
