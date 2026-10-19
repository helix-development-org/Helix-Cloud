package org.helix.addons.stats

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseWipeTest {
    @Test
    fun `stats and seasons are counted and wiped`() {
        val storage = InMemoryAddonStorage()
        val stats = StatsStore(storage)
        val seasons = SeasonStore(storage, stats)
        stats.add("mineclash.wins", "alice", 3)
        stats.add("mineclash.wins", "bob", 1)
        seasons.reset("mineclash.wins", 1L)
        stats.add("citybuild.arena.kills", "alice", 2)
        assertEquals(1, stats.wipe(dryRun = true))
        assertEquals(1, seasons.wipe(dryRun = true))
        assertEquals(2, stats.get("citybuild.arena.kills", "alice"))
        stats.wipe(dryRun = false)
        seasons.wipe(dryRun = false)
        assertEquals(0, StatsStore(storage).get("citybuild.arena.kills", "alice"))
        assertEquals(0, SeasonStore(storage, StatsStore(storage)).wipe(dryRun = true))
    }
}
