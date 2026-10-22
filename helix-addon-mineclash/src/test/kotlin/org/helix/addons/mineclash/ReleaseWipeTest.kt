package org.helix.addons.mineclash

import org.helix.addons.mineclash.profile.DocumentProfileRepository
import org.helix.addons.mineclash.profile.MatchRecord
import org.helix.addons.mineclash.profile.PlayerProfile
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseWipeTest {
    @Test
    fun `profiles and match history are counted and wiped`() {
        val storage = InMemoryAddonStorage()
        storage.write("config.settings", "{}")
        val repo = DocumentProfileRepository(storage)
        repo.save(PlayerProfile("u1", "alice"))
        repo.save(PlayerProfile("u2", "bob"))
        repo.recordMatch(MatchRecord("duel", 1, listOf(listOf("u1"), listOf("u2"))))
        assertEquals(mapOf("profiles" to 2, "match_history" to 1), repo.wipe(dryRun = true))
        repo.wipe(dryRun = false)
        assertNull(repo.load("u1"))
        assertEquals(mapOf("profiles" to 0, "match_history" to 0), repo.wipe(dryRun = true))
        assertEquals("{}", storage.read("config.settings"), "configuration is kept")
    }
}
