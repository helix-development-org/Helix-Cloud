package org.helix.addons.maprotation

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationStoreTest {
    @Test
    fun `configure starts at the first map`() {
        val store = RotationStore(InMemoryAddonStorage())
        store.configure("skywars", listOf("island1", "island2", "island3"))

        assertEquals("island1", store.current("skywars"))
        assertEquals("island2", store.peekNext("skywars"))
    }

    @Test
    fun `advance cycles through and wraps around`() {
        val store = RotationStore(InMemoryAddonStorage())
        store.configure("skywars", listOf("island1", "island2", "island3"))

        assertEquals("island2", store.advance("skywars"))
        assertEquals("island3", store.advance("skywars"))
        assertEquals("island1", store.advance("skywars"))
    }

    @Test
    fun `peekNext does not change current`() {
        val store = RotationStore(InMemoryAddonStorage())
        store.configure("skywars", listOf("island1", "island2"))

        store.peekNext("skywars")
        store.peekNext("skywars")

        assertEquals("island1", store.current("skywars"))
    }

    @Test
    fun `unknown rotation yields null and configure rejects an empty list`() {
        val store = RotationStore(InMemoryAddonStorage())

        assertNull(store.current("unknown"))
        assertNull(store.peekNext("unknown"))
        assertNull(store.advance("unknown"))
        assertFailsWith<IllegalArgumentException> { store.configure("empty", emptyList()) }
    }

    @Test
    fun `reconfigure keeps the cursor on the current map when it still exists`() {
        val store = RotationStore(InMemoryAddonStorage())
        store.configure("skywars", listOf("island1", "island2", "island3"))
        store.advance("skywars")
        assertEquals("island2", store.current("skywars"))

        store.configure("skywars", listOf("island0", "island2", "island1"))

        assertEquals("island2", store.current("skywars"))
    }

    @Test
    fun `reconfigure resets to the first map when the current one is dropped`() {
        val store = RotationStore(InMemoryAddonStorage())
        store.configure("skywars", listOf("island1", "island2"))
        store.advance("skywars")
        assertEquals("island2", store.current("skywars"))

        store.configure("skywars", listOf("island3", "island4"))

        assertEquals("island3", store.current("skywars"))
    }

    @Test
    fun `remove deletes a rotation`() {
        val store = RotationStore(InMemoryAddonStorage())
        store.configure("skywars", listOf("island1"))

        assertTrue(store.remove("skywars"))
        assertFalse(store.remove("skywars"))
        assertNull(store.current("skywars"))
    }

    @Test
    fun `rotationIds and mapsOf report configured rotations`() {
        val store = RotationStore(InMemoryAddonStorage())
        store.configure("Skywars", listOf("island1", "island2"))
        store.configure("bedwars", listOf("map1"))

        assertEquals(listOf("bedwars", "skywars"), store.rotationIds())
        assertEquals(listOf("island1", "island2"), store.mapsOf("SKYWARS"))
    }

    @Test
    fun `state persists across instances`() {
        val storage = InMemoryAddonStorage()
        RotationStore(storage).apply {
            configure("skywars", listOf("island1", "island2"))
            advance("skywars")
        }

        assertEquals("island2", RotationStore(storage).current("skywars"))
    }
}
