package org.helix.addons.friends

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendStoreTest {
    @Test
    fun `a friendship formed while uuids are unresolvable falls back to name keys`() {
        val store = FriendStore(InMemoryAddonStorage(), resolveUuid = { null })
        store.request("steve", "alex")
        store.accept("alex", "steve")

        assertTrue(store.areFriends("Steve", "Alex"))
        assertEquals(listOf("alex"), store.friendsOf("steve"))
    }

    @Test
    fun `a legacy name-keyed friendship migrates to uuid the moment it becomes resolvable`() {
        val storage = InMemoryAddonStorage()
        val uuids = mutableMapOf<String, String>()
        val store = FriendStore(storage, resolveUuid = { uuids[it] })
        store.request("steve", "alex")
        store.accept("alex", "steve")

        uuids["steve"] = "uuid-1"
        uuids["alex"] = "uuid-2"

        assertTrue(store.areFriends("Steve", "Alex"))
        // reloading confirms the migration was persisted, not just held in memory
        val reloaded = FriendStore(storage, resolveUuid = { uuids[it] })
        assertTrue(reloaded.areFriends("Steve", "Alex"))
        assertEquals(listOf("alex"), reloaded.friendsOf("steve"))
    }

    @Test
    fun `a rename does not break an existing friendship`() {
        val uuids = mutableMapOf("steve" to "uuid-1", "alex" to "uuid-2")
        val store = FriendStore(InMemoryAddonStorage(), resolveUuid = { uuids[it] })
        store.request("steve", "alex")
        store.accept("alex", "steve")

        // Steve renames; the name index would point "steve2" at uuid-1 from here on
        uuids.remove("steve")
        uuids["steve2"] = "uuid-1"

        assertTrue(store.areFriends("Steve2", "Alex"))
        assertEquals(listOf("alex"), store.friendsOf("Steve2"))
    }

    @Test
    fun `a freed name reused by a different uuid is not automatically friends with anyone`() {
        val uuids = mutableMapOf("steve" to "uuid-1", "alex" to "uuid-2")
        val store = FriendStore(InMemoryAddonStorage(), resolveUuid = { uuids[it] })
        store.request("steve", "alex")
        store.accept("alex", "steve")

        uuids["steve"] = "uuid-3"

        assertFalse(store.areFriends("Steve", "Alex"))
        assertTrue(store.friendsOf("Steve").isEmpty())
    }

    @Test
    fun `store persists across instances`() {
        val storage = InMemoryAddonStorage()
        val first = FriendStore(storage)
        first.request("steve", "alex")
        first.accept("alex", "steve")

        assertTrue(FriendStore(storage).areFriends("Steve", "Alex"))
    }
}
