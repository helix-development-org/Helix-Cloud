package org.helix.addons.economy

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseWipeTest {
    @Test
    fun `plan counts, run deletes accounts, ledgers and counters`() {
        val storage = InMemoryAddonStorage()
        val repo = DocumentBalanceRepository(storage, 1000)
        repo.change(Holder("u1", "alice"), 50, "job", limit = DailyLimit("citybuild.jobs", 4000))
        repo.change(Holder("u2", "bob"), -10, "shop")
        val plan = repo.wipeAll(dryRun = true)
        assertEquals(mapOf("accounts" to 2, "ledger" to 2, "daily_counters" to 1), plan)
        assertEquals(1050, repo.balance("u1"), "a plan changes nothing")

        repo.wipeAll(dryRun = false)
        assertNull(repo.account("u1"))
        assertEquals(1000, repo.balance("u1"), "everyone starts again with the starting balance")
        assertEquals(0, repo.history("u1", 10).size)
        assertEquals(0, repo.dailyUsed("u1", "citybuild.jobs"))
        assertEquals(mapOf("accounts" to 0, "ledger" to 0, "daily_counters" to 0), DocumentBalanceRepository(storage, 1000).wipeAll(dryRun = true))
    }
}
