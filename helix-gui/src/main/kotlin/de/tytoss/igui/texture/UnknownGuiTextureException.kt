package de.tytoss.igui.texture

/**
 * Thrown when a texture id cannot be resolved: it is missing from the
 * configured [de.tytoss.igui.database.GuiTextureDatabase], regardless of
 * which backend is in use.
 *
 * @property textureId the texture id that could not be resolved.
 */
class UnknownGuiTextureException(val textureId: String) :
    NoSuchElementException("Texture '$textureId' was not found in PostgreSQL")
