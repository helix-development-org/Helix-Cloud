package org.helix.addons.economy

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyMigrationTest {
    private val storage = InMemoryAddonStorage().apply { write(LegacyMigration.LEGACY, """{"steve":250,"ghost":70}""") }
    private val repo = DocumentBalanceRepository(storage, 1000)
    private val steveUuid = "00000000-0000-0000-0000-00000000000a"
    private val migration = LegacyMigration(storage, repo) { name -> if (name == "steve") steveUuid else null }

    @Test
    fun `resolvable names are imported, others parked, and it runs once`() {
        assertEquals(1, migration.run())
        assertEquals(250, repo.balance(steveUuid))
        assertNull(storage.read(LegacyMigration.LEGACY))
        assertTrue(storage.read(LegacyMigration.MIGRATED)!!.contains("ghost"))
        assertEquals(0, migration.run(), "the legacy document is gone, nothing runs twice")
    }

    @Test
    fun `a parked balance is claimed once on join`() {
        migration.run()
        val ghost = Holder("00000000-0000-0000-0000-00000000000d", "Ghost")

        assertTrue(migration.claim(ghost))
        assertEquals(70, repo.balance(ghost.uuid))
        assertFalse(migration.claim(ghost))
    }

    @Test
    fun `an account that already booked keeps its balance`() {
        repo.change(Holder(steveUuid, "Steve"), 5, "job")
        migration.run()
        assertEquals(1005, repo.balance(steveUuid))
    }
}
