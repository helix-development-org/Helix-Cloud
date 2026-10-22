package org.helix.addons.clan

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReleaseWipeTest {
    @Test
    fun `release empties clan banks but keeps the clans`() {
        val storage = InMemoryAddonStorage()
        val store = ClanStore(storage)
        val clan = assertNotNull(store.create("ABC", "Alpha", "alice", 1L))
        val id = store.resolveId("ABC")!!
        store.adjustBank(id, 750)
        assertEquals(1 to 750L, store.emptyBanks(dryRun = true))
        assertEquals(750L, store.clanById(id)!!.bank)
        store.emptyBanks(dryRun = false)
        val reloaded = ClanStore(storage)
        assertEquals(0L, reloaded.clanById(id)!!.bank)
        assertEquals(clan.name, reloaded.clanById(id)!!.name)
        assertEquals(0 to 0L, reloaded.emptyBanks(dryRun = true))
    }
}
