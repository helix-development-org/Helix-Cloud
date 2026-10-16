package org.helix.addons.economy

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentBalanceRepositoryTest : BalanceRepositoryContract() {
    private val storage = InMemoryAddonStorage()

    override fun create(): BalanceRepository = DocumentBalanceRepository(storage, 1000, clock)

    @Test
    fun `accounts and ledger survive a restart`() {
        val holder = Holder("00000000-0000-0000-0000-00000000000c", "Kim")
        DocumentBalanceRepository(storage, 1000, clock).change(holder, 5, "job")

        val reopened = DocumentBalanceRepository(storage, 1000, clock)
        assertEquals(1005, reopened.balance(holder.uuid))
        assertEquals(1, reopened.history(holder.uuid, 10).size)
        reopened.change(holder, 1, "job")
        assertEquals(listOf(2L, 1L), reopened.history(holder.uuid, 10).map { it.id })
    }
}
