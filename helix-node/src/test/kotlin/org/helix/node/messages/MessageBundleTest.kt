package org.helix.node.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.api.storage.InMemoryAddonStorage

class MessageBundleTest {
    @Test
    fun `defaults seed storage and format substitutes placeholders`() {
        val storage = InMemoryAddonStorage()
        val bundle = MessageBundle(storage, mapOf("hi" to "&aHello {name}!"))

        assertEquals("&aHello Steve!", bundle.format("hi", "name" to "Steve"))
        assertTrue(storage.read("messages")!!.contains("hi"))
    }

    @Test
    fun `edits persist and survive reload but keep unknown-key safety`() {
        val storage = InMemoryAddonStorage()
        MessageBundle(storage, mapOf("hi" to "Hello {name}")).also {
            assertTrue(it.set("hi", "Hi {name}!"))
            assertFalse(it.set("unknown", "x"))
        }

        val reloaded = MessageBundle(storage, mapOf("hi" to "Hello {name}"))
        assertEquals("Hi Steve!", reloaded.format("hi", "name" to "Steve"))
    }

    @Test
    fun `new defaults are added without overwriting edited ones`() {
        val storage = InMemoryAddonStorage()
        MessageBundle(storage, mapOf("a" to "one")).set("a", "edited")

        val upgraded = MessageBundle(storage, mapOf("a" to "one", "b" to "two"))

        assertEquals("edited", upgraded.raw("a"))
        assertEquals("two", upgraded.raw("b"))
    }

    @Test
    fun `reset restores the default`() {
        val bundle = MessageBundle(InMemoryAddonStorage(), mapOf("a" to "default"))
        bundle.set("a", "changed")

        assertTrue(bundle.reset("a"))
        assertEquals("default", bundle.raw("a"))
    }

    @Test
    fun `registry lists and edits across addons`() {
        val registry = MessageRegistry()
        registry.register("helix.bans", MessageBundle(InMemoryAddonStorage(), mapOf("k" to "v")))
        registry.register("helix.friends", MessageBundle(InMemoryAddonStorage(), mapOf("j" to "w")))

        assertEquals(setOf("helix.bans", "helix.friends"), registry.all().keys)
        assertTrue(registry.set("helix.bans", "k", "new"))
        assertFalse(registry.set("helix.bans", "missing", "x"))
        assertEquals("new", registry.all()["helix.bans"]!!["k"])

        registry.unregisterOwner("helix.bans")
        assertEquals(setOf("helix.friends"), registry.all().keys)
    }
}
