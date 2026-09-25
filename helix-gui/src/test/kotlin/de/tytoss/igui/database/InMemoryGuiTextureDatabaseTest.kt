package de.tytoss.igui.database

import de.tytoss.igui.texture.GuiTextureDefinition
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.kyori.adventure.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryGuiTextureDatabaseTest {
    private fun definition(id: String) =
        GuiTextureDefinition(id, "", Key.key("test", "gui"), widthPixels = 176, heightPixels = 222)

    @Test
    fun `stores replaces and removes textures`() = runBlocking {
        val database = InMemoryGuiTextureDatabase(listOf(definition("a")))
        assertEquals(listOf("a"), database.textures().map { it.id })
        database.put(definition("b"))
        assertEquals(setOf("a", "b"), database.textures().map { it.id }.toSet())
        assertEquals("b", database.texture("b")?.id)
        assertTrue(database.remove("a"))
        assertFalse(database.remove("a"))
        assertNull(database.texture("a"))
        database.close()
    }

    @Test
    fun `announces changes`() = runBlocking {
        val database = InMemoryGuiTextureDatabase()
        val next = async { database.changes().first() }
        yield()
        database.put(definition("c"))
        assertEquals(GuiTextureChange(GuiTextureChangeType.UPSERT, "c"), next.await())
    }
}
