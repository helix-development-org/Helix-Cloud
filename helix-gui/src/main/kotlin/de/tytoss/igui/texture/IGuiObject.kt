package de.tytoss.igui.texture

/**
 * A statically-known texture reference an addon can hold onto (typically as
 * an `object` singleton) instead of re-resolving a texture id on every use.
 * Register instances via [de.tytoss.igui.IGuiConfiguration.textures]/[de.tytoss.igui.IGuiConfiguration.texture]
 * so [de.tytoss.igui.IGui.install] binds their [texture] before returning;
 * kept in sync afterwards as the cache is reloaded or updated.
 *
 * @property id the texture id this object refers to.
 */
abstract class IGuiObject(val id: String) {
    @Volatile
    internal var attachment: GuiTexture? = null

    init {
        require(id.isNotBlank()) { "Texture id must not be blank" }
    }

    /**
     * The currently bound texture.
     *
     * @throws IllegalStateException if this object was never registered via
     *  [de.tytoss.igui.IGuiConfiguration.textures], or its texture has since
     *  been removed from the cache.
     */
    val texture: GuiTexture
        get() = attachment ?: error(
            "Texture '$id' is not installed. " +
                    "Register it in IGui.install { textures(...) }.",
        )
}
