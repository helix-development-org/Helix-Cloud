package org.helix.addons.stats

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatsStoreTest {
    @Test
    fun `add accumulates and set overwrites`() {
        val store = StatsStore(InMemoryAddonStorage())

        assertEquals(5, store.add("kills", "Steve", 5))
        assertEquals(8, store.add("kills", "Steve", 3))
        assertEquals(0, store.add("kills", "Steve", -8))

        store.set("kills", "Steve", 42)
        assertEquals(42, store.get("kills", "steve"))
    }

    @Test
    fun `unrecorded stat defaults to zero`() {
        val store = StatsStore(InMemoryAddonStorage())
        assertEquals(0, store.get("kills", "Alex"))
    }

    @Test
    fun `stats are case-insensitive and independent per key`() {
        val store = StatsStore(InMemoryAddonStorage())
        store.add("Kills", "Steve", 3)
        store.add("kills", "STEVE", 2)
        store.add("deaths", "Steve", 1)

        assertEquals(5, store.get("KILLS", "steve"))
        assertEquals(1, store.get("deaths", "steve"))
    }

    @Test
    fun `top sorts descending with alphabetical tiebreak and respects limit`() {
        val store = StatsStore(InMemoryAddonStorage())
        store.set("kills", "alex", 10)
        store.set("kills", "steve", 20)
        store.set("kills", "bob", 20)
        store.set("kills", "zed", 5)

        val top = store.top("kills", 2)

        assertEquals(listOf("bob" to 20L, "steve" to 20L), top)
    }

    @Test
    fun `statKeys lists only keys with recorded values`() {
        val store = StatsStore(InMemoryAddonStorage())
        assertTrue(store.statKeys().isEmpty())

        store.add("kills", "steve", 1)
        store.add("deaths", "steve", 1)

        assertEquals(listOf("deaths", "kills"), store.statKeys())
    }

    @Test
    fun `clear removes only the targeted stat`() {
        val store = StatsStore(InMemoryAddonStorage())
        store.add("kills", "steve", 1)
        store.add("deaths", "steve", 1)

        assertTrue(store.clear("kills"))
        assertFalse(store.clear("kills"))
        assertEquals(0, store.get("kills", "steve"))
        assertEquals(1, store.get("deaths", "steve"))
    }

    @Test
    fun `store persists across instances`() {
        val storage = InMemoryAddonStorage()
        StatsStore(storage).add("kills", "steve", 7)

        assertEquals(7, StatsStore(storage).get("kills", "steve"))
    }
}
