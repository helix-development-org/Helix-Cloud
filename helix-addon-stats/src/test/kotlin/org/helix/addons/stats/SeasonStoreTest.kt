package org.helix.addons.stats

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonStoreTest {
    @Test
    fun `reset archives standings then clears the live stat`() {
        val storage = InMemoryAddonStorage()
        val stats = StatsStore(storage)
        val seasons = SeasonStore(storage, stats)
        stats.set("kills", "steve", 20)
        stats.set("kills", "alex", 30)

        val record = seasons.reset("kills", 1_000L)

        requireNotNull(record)
        assertEquals(1, record.season)
        assertEquals(listOf(PlayerScore("alex", 30), PlayerScore("steve", 20)), record.standings)
        assertEquals(0, stats.get("kills", "steve"))
        assertEquals(0, stats.get("kills", "alex"))
    }

    @Test
    fun `reset on an empty stat archives nothing`() {
        val storage = InMemoryAddonStorage()
        val stats = StatsStore(storage)
        val seasons = SeasonStore(storage, stats)

        assertNull(seasons.reset("kills", 1_000L))
        assertTrue(seasons.seasons("kills").isEmpty())
    }

    @Test
    fun `season numbers increment and past seasons stay viewable after further play`() {
        val storage = InMemoryAddonStorage()
        val stats = StatsStore(storage)
        val seasons = SeasonStore(storage, stats)
        stats.set("kills", "steve", 10)
        val first = requireNotNull(seasons.reset("kills", 1_000L))

        stats.add("kills", "steve", 5)
        val second = requireNotNull(seasons.reset("kills", 2_000L))

        assertEquals(1, first.season)
        assertEquals(2, second.season)
        assertEquals(first, seasons.season("kills", 1))
        assertEquals(second, seasons.season("kills", 2))
        assertNull(seasons.season("kills", 3))
    }

    @Test
    fun `archive persists across instances`() {
        val storage = InMemoryAddonStorage()
        val stats = StatsStore(storage)
        stats.set("kills", "steve", 10)
        SeasonStore(storage, stats).reset("kills", 1_000L)

        val reloaded = SeasonStore(storage, StatsStore(storage))
        assertEquals(1, reloaded.seasons("kills").single().season)
    }
}
