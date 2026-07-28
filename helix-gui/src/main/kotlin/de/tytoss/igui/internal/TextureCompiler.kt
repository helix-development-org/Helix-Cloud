package de.tytoss.igui.internal

import de.tytoss.igui.texture.GuiTexture
import de.tytoss.igui.texture.GuiTextureDefinition

/** Converts stored [GuiTextureDefinition]s into ready-to-render [GuiTexture]s. */
internal object TextureCompiler {
    /**
     * Compiles a stored definition into a renderable texture, pre-building
     * its default-color [net.kyori.adventure.text.Component].
     *
     * @param definition the definition to compile.
     * @return the compiled, cacheable texture.
     */
    fun compile(definition: GuiTextureDefinition): GuiTexture = GuiTexture(
        id = definition.id,
        character = definition.character,
        font = definition.font,
        widthPixels = definition.widthPixels,
        heightPixels = definition.heightPixels,
        advancePixels = definition.advancePixels,
        clientAnimated = definition.clientAnimated,
    )
}
