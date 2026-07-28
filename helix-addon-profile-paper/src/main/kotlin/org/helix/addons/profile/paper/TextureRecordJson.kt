package org.helix.addons.profile.paper

import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.serialization.Serializable
import net.kyori.adventure.key.Key

/**
 * Wire form of a [GuiTextureDefinition], with [Key] flattened to its plain
 * string form for JSON transport (matches the profile addon's own
 * `GuiTextureRecord` shape field-for-field, without either module
 * depending on the other's Kotlin classes).
 */
@Serializable
data class TextureRecordJson(
    val id: String,
    val character: String,
    val font: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val advancePixels: Int,
    val clientAnimated: Boolean,
) {
    /** Converts back to the real IGui type. */
    fun toDefinition(): GuiTextureDefinition = GuiTextureDefinition(
        id = id,
        character = character,
        font = Key.key(font),
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        advancePixels = advancePixels,
        clientAnimated = clientAnimated,
    )

    companion object {
        /**
         * Flattens a real IGui texture definition into its wire form.
         *
         * @param definition the definition to flatten.
         * @return the wire form.
         */
        fun from(definition: GuiTextureDefinition): TextureRecordJson = TextureRecordJson(
            id = definition.id,
            character = definition.character,
            font = definition.font.asString(),
            widthPixels = definition.widthPixels,
            heightPixels = definition.heightPixels,
            advancePixels = definition.advancePixels,
            clientAnimated = definition.clientAnimated,
        )
    }
}
