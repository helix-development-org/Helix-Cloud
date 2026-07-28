package org.helix.addons.bans

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.helix.api.storage.InMemoryAddonStorage

class BanStoreTest {
    @Test
    fun `a ban set while the uuid is unresolvable falls back to a name-keyed entry`() {
        val store = BanStore(InMemoryAddonStorage(), resolveUuid = { null })

        val entry = store.set("Offline", "afk ban")

        assertNull(entry.uuid)
        assertEquals("afk ban", store.activeBan("offline")?.reason)
    }

    @Test
    fun `a legacy name-keyed ban migrates to uuid the moment it becomes resolvable`() {
        val storage = InMemoryAddonStorage()
        var uuid: String? = null
        val store = BanStore(storage, resolveUuid = { uuid })
        store.set("Steve", "griefing")

        uuid = "uuid-1"
        val migrated = store.activeBan("Steve")

        assertEquals("uuid-1", migrated?.uuid)
        // reloading from storage confirms the migration was persisted, not just in memory
        assertEquals("uuid-1", BanStore(storage, resolveUuid = { uuid }).activeBan("steve")?.uuid)
    }

    @Test
    fun `a rename does not evade a uuid-keyed ban`() {
        val store = BanStore(InMemoryAddonStorage(), resolveUuid = { "uuid-1" })
        store.set("Steve", "griefing")

        // the account renamed; only the uuid stays constant, exactly what activeBan is given here
        assertEquals("griefing", store.activeBan("Steve2", uuid = "uuid-1")?.reason)
    }

    @Test
    fun `an unrelated player taking the freed name is not affected by another uuid's ban`() {
        val store = BanStore(InMemoryAddonStorage(), resolveUuid = { "uuid-1" })
        store.set("Steve", "griefing")

        assertNull(store.activeBan("Steve", uuid = "uuid-2"))
    }
}
