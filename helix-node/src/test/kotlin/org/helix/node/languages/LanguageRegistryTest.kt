package org.helix.node.languages

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageRegistryTest {
    @Test
    fun `ships english and german with english default`() {
        val languages = LanguageRegistry(InMemoryAddonStorage())

        assertEquals(listOf("en", "de"), languages.languages())
        assertEquals("en", languages.defaultLanguage())
        assertEquals("en", languages.languageOf("Steve"))
    }

    @Test
    fun `player choice persists and beats the client locale`() {
        val storage = InMemoryAddonStorage()
        val languages = LanguageRegistry(storage)

        assertTrue(languages.setPlayerLanguage("Erik", "de"))
        assertFalse(languages.setPlayerLanguage("Erik", "xx"))
        assertFalse(languages.applyClientLocale("Erik", "en_us"))

        val reloaded = LanguageRegistry(storage)
        assertEquals("de", reloaded.languageOf("erik"))
    }

    @Test
    fun `first join locale sets the initial language once`() {
        val languages = LanguageRegistry(InMemoryAddonStorage())

        assertTrue(languages.applyClientLocale("Anna", "de_DE"))
        assertEquals("de", languages.languageOf("Anna"))
        // unknown locales leave the default
        assertFalse(languages.applyClientLocale("Bob", "fr_fr"))
        assertEquals("en", languages.languageOf("Bob"))
    }

    @Test
    fun `languages can be added removed and made default`() {
        val languages = LanguageRegistry(InMemoryAddonStorage())

        assertTrue(languages.addLanguage("fr"))
        assertFalse(languages.addLanguage("fr"))
        assertFalse(languages.addLanguage("Nope!"))
        assertTrue(languages.setDefaultLanguage("fr"))
        assertFalse(languages.removeLanguage("fr"))

        languages.setPlayerLanguage("Erik", "de")
        assertTrue(languages.removeLanguage("de"))
        assertEquals("fr", languages.languageOf("Erik"))
    }
}
