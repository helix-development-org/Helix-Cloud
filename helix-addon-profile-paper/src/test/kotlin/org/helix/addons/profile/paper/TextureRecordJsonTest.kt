package org.helix.addons.profile.paper

import de.tytoss.igui.texture.GuiTextureDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kyori.adventure.key.Key

class TextureRecordJsonTest {
    private val definition = GuiTextureDefinition(
        id = "header",
        character = "",
        font = Key.key("profile", "default"),
        widthPixels = 176,
        heightPixels = 18,
        advancePixels = 177,
        clientAnimated = false,
    )

    @Test
    fun `from flattens the key to its plain string form`() {
        val record = TextureRecordJson.from(definition)

        assertEquals("profile:default", record.font)
        assertEquals(definition.id, record.id)
        assertEquals(definition.character, record.character)
        assertEquals(definition.widthPixels, record.widthPixels)
        assertEquals(definition.heightPixels, record.heightPixels)
        assertEquals(definition.advancePixels, record.advancePixels)
        assertEquals(definition.clientAnimated, record.clientAnimated)
    }

    @Test
    fun `toDefinition round-trips back to an equal definition`() {
        val roundTripped = TextureRecordJson.from(definition).toDefinition()

        assertEquals(definition, roundTripped)
    }
}
