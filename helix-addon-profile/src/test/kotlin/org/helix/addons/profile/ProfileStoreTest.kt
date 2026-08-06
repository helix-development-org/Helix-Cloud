package org.helix.addons.profile

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileStoreTest {
    @Test
    fun `set and get round-trip per player, owner and key`() {
        val store = ProfileStore(InMemoryAddonStorage())

        store.set("Steve", "cosmetics", "wings", "angel")
        store.set("Steve", "subtitles", "subtitle", "Veteran")

        assertEquals("angel", store.get("steve", "cosmetics", "wings"))
        assertEquals("Veteran", store.get("STEVE", "subtitles", "subtitle"))
        assertNull(store.get("steve", "cosmetics", "headwear"))
        assertNull(store.get("alex", "cosmetics", "wings"))
    }

    @Test
    fun `allFor returns every value of a player, flat-keyed`() {
        val store = ProfileStore(InMemoryAddonStorage())
        store.set("Steve", "cosmetics", "wings", "angel")
        store.set("Steve", "cosmetics", "headwear", "crown")

        assertEquals(mapOf("cosmetics:wings" to "angel", "cosmetics:headwear" to "crown"), store.allFor("steve"))
        assertTrue(store.allFor("alex").isEmpty())
    }

    @Test
    fun `clear removes only the targeted setting`() {
        val store = ProfileStore(InMemoryAddonStorage())
        store.set("Steve", "cosmetics", "wings", "angel")
        store.set("Steve", "cosmetics", "headwear", "crown")

        assertTrue(store.clear("Steve", "cosmetics", "wings"))
        assertNull(store.get("steve", "cosmetics", "wings"))
        assertEquals("crown", store.get("steve", "cosmetics", "headwear"))
        assertFalse(store.clear("Steve", "cosmetics", "wings"), "clearing an already-cleared setting reports false")
    }

    @Test
    fun `persists across instances`() {
        val storage = InMemoryAddonStorage()
        ProfileStore(storage).set("Steve", "cosmetics", "wings", "angel")

        assertEquals("angel", ProfileStore(storage).get("steve", "cosmetics", "wings"))
    }
}
