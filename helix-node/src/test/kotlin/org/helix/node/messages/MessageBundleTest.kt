package org.helix.node.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.storage.InMemoryAddonStorage

class MessageBundleTest {
    private fun bundle(
        storage: InMemoryAddonStorage = InMemoryAddonStorage(),
        defaults: Map<String, Map<String, String>> = mapOf(
            "en" to mapOf("hi" to "&aHello {name}!", "bye" to "Bye."),
            "de" to mapOf("hi" to "&aHallo {name}!"),
        ),
        playerLanguages: Map<String, String> = mapOf("erik" to "de"),
    ) = MessageBundle(
        storage = storage,
        defaults = defaults,
        defaultLanguage = { "en" },
        languageOf = { player -> playerLanguages[player.lowercase()] ?: "en" },
    )

    @Test
    fun `format resolves the default language and substitutes placeholders`() {
        assertEquals("&aHello Steve!", bundle().format("hi", "name" to "Steve"))
    }

    @Test
    fun `formatFor resolves the player language with default fallback`() {
        val bundle = bundle()

        assertEquals("&aHallo Erik!", bundle.formatFor("Erik", "hi", "name" to "Erik"))
        // `bye` has no German default — falls back to English.
        assertEquals("Bye.", bundle.formatFor("Erik", "bye"))
        assertEquals("&aHello Steve!", bundle.formatFor("Steve", "hi", "name" to "Steve"))
    }

    @Test
    fun `edits persist per language and survive reload`() {
        val storage = InMemoryAddonStorage()
        bundle(storage).also {
            assertTrue(it.set("de", "hi", "Servus {name}!"))
            assertTrue(it.set("en", "created", "Brand new"))
        }

        val reloaded = bundle(storage)
        assertEquals("Servus Erik!", reloaded.formatFor("Erik", "hi", "name" to "Erik"))
        assertEquals("Brand new", reloaded.raw("created"))
        assertTrue("created" in reloaded.keys())
    }

    @Test
    fun `reset removes the custom value and restores the default`() {
        val bundle = bundle()
        bundle.set("en", "hi", "changed")

        assertTrue(bundle.reset("en", "hi"))
        assertFalse(bundle.reset("en", "hi"))
        assertEquals("&aHello {name}!", bundle.raw("hi"))
    }

    @Test
    fun `deleteKey removes custom keys but never default-backed ones`() {
        val bundle = bundle()
        bundle.set("en", "custom.key", "x")
        bundle.set("de", "custom.key", "y")

        assertTrue(bundle.deleteKey("custom.key"))
        assertFalse("custom.key" in bundle.keys())
        assertFalse(bundle.deleteKey("hi"))
    }

    @Test
    fun `legacy single-language documents migrate edited values into english`() {
        val storage = InMemoryAddonStorage()
        // pre-multilingual format: flat map including seeded (unedited) defaults
        storage.write("messages", """{"hi": "&aHello {name}!", "bye": "Edited bye"}""")

        val migrated = bundle(storage)

        assertEquals("Edited bye", migrated.raw("bye"))
        // unedited default was not frozen as a custom value
        assertNull(migrated.customValues()["en"]?.get("hi"))
    }

    @Test
    fun `registry flattens keys and routes flat edits by longest owner match`() {
        val registry = MessageRegistry()
        registry.register("velocity", bundle())
        registry.register(
            "helix.friends",
            MessageBundle(InMemoryAddonStorage(), mapOf("en" to mapOf("joined" to "hey"))),
        )

        val keys = registry.entries().map { it.key }
        assertTrue("helix.translations.velocity.hi" in keys)
        assertTrue("helix.translations.helix.friends.joined" in keys)

        assertTrue(registry.set("helix.translations.helix.friends.joined", "de", "hallo"))
        assertEquals(
            "hallo",
            registry.effectiveTables(listOf("de"))["de"]!!["helix.translations.helix.friends.joined"],
        )
        assertFalse(registry.set("helix.translations.unknown.owner.key", "en", "x"))
        assertEquals("helix.friends", registry.ownerOf("helix.translations.helix.friends.joined"))
    }

    @Test
    fun `registry delete only removes custom keys`() {
        val registry = MessageRegistry()
        registry.register("custom", MessageBundle(InMemoryAddonStorage(), emptyMap()))
        registry.set("helix.translations.custom.motd.extra", "en", "hi")

        assertTrue(registry.deleteKey("helix.translations.custom.motd.extra"))
        assertFalse(registry.deleteKey("helix.translations.custom.motd.extra"))
    }
}
