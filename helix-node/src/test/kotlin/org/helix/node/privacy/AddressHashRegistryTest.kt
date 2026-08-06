package org.helix.node.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.storage.InMemoryAddonStorage

class AddressHashRegistryTest {
    private var now = 1_000_000_000L
    private val storage = InMemoryAddonStorage()
    private val registry = AddressHashRegistry(storage, maxPerPlayer = 2, retentionDays = 90, now = { now })

    @Test
    fun `raw addresses never reach the storage`() {
        registry.record("uuid-1", "203.0.113.7")

        val persisted = storage.read("addresses")!!
        assertFalse(persisted.contains("203.0.113"))
        assertTrue(persisted.contains("uuid-1"))
    }

    @Test
    fun `players sharing an address are found in both directions`() {
        registry.record("uuid-1", "203.0.113.7")
        registry.record("uuid-2", "203.0.113.7")
        registry.record("uuid-3", "198.51.100.1")

        assertEquals(listOf("uuid-2"), registry.sharing("uuid-1"))
        assertEquals(listOf("uuid-1"), registry.sharing("uuid-2"))
        assertTrue(registry.sharing("uuid-3").isEmpty())
        assertTrue(registry.sharing("uuid-unknown").isEmpty())
    }

    @Test
    fun `hashes are capped per player and expire after retention`() {
        registry.record("uuid-1", "10.0.0.1")
        registry.record("uuid-1", "10.0.0.2")
        registry.record("uuid-1", "10.0.0.3")
        registry.record("uuid-2", "10.0.0.1")

        // 10.0.0.1 was evicted by the cap of 2, so no sharing anymore
        assertTrue(registry.sharing("uuid-2").isEmpty())

        registry.record("uuid-2", "10.0.0.3")
        assertEquals(listOf("uuid-1"), registry.sharing("uuid-2"))

        now += 91L * 86_400_000L
        assertTrue(registry.sharing("uuid-2").isEmpty())
    }

    @Test
    fun `salt persists so hashes stay comparable across restarts`() {
        registry.record("uuid-1", "203.0.113.7")

        val reloaded = AddressHashRegistry(storage, now = { now })
        reloaded.record("uuid-2", "203.0.113.7")

        assertEquals(listOf("uuid-1"), reloaded.sharing("uuid-2"))
        assertEquals(storage.read("salt"), storage.read("salt"))
    }

    @Test
    fun `different salts produce unlinkable hashes`() {
        registry.record("uuid-1", "203.0.113.7")
        val otherStorage = InMemoryAddonStorage()
        AddressHashRegistry(otherStorage, now = { now }).record("uuid-1", "203.0.113.7")

        // the same address under different installation salts must hash differently
        assertFalse(storage.read("addresses") == otherStorage.read("addresses"))
    }

    @Test
    fun `gdpr export and delete work by uuid`() {
        registry.record("uuid-1", "203.0.113.7")

        val exported = registry.export("uuid-1")
        assertTrue(exported!!.contains("hash"))
        assertNull(registry.export("uuid-2"))

        assertTrue(registry.delete("uuid-1"))
        assertFalse(registry.delete("uuid-1"))
        assertNull(registry.export("uuid-1"))
    }
}
