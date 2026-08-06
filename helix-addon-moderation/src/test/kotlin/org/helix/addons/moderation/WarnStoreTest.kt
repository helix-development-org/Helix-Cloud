package org.helix.addons.moderation

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarnStoreTest {
    @Test
    fun `warn records history and persists across instances`() {
        val storage = InMemoryAddonStorage()
        val store = WarnStore(storage, clock = { 1_000L })

        store.warn("Steve", "Mod", "spamming")
        store.warn("steve", "Mod", "again")
        store.warn("Alex", "Mod", "griefing")

        assertEquals(2, store.warnsOf("STEVE").size)
        assertEquals(1, WarnStore(storage, clock = { 1_000L }).warnsOf("alex").size)
    }

    @Test
    fun `a legacy un-enveloped document (pre-SchemaMigrator) still loads`() {
        val storage = InMemoryAddonStorage()
        storage.write(
            "warns",
            """[{"player":"steve","by":"Mod","reason":"legacy","atEpochMs":1}]""",
        )

        val store = WarnStore(storage, clock = { 1_000L })

        assertEquals(1, store.warnsOf("steve").size)
        assertEquals("legacy", store.warnsOf("steve").first().reason)
    }

    @Test
    fun `a warn recorded while the uuid is unresolvable falls back to name matching`() {
        val store = WarnStore(InMemoryAddonStorage(), resolveUuid = { null })

        val entry = store.warn("Offline", "Mod", "afk")

        assertEquals(null, entry.uuid)
        assertEquals(1, store.warnsOf("offline").size)
    }

    @Test
    fun `a legacy name-only warn is tagged with the uuid the moment it becomes known`() {
        val storage = InMemoryAddonStorage()
        var uuid: String? = null
        val store = WarnStore(storage, resolveUuid = { uuid })
        store.warn("Steve", "Mod", "spamming")

        uuid = "uuid-1"
        val history = store.warnsOf("Steve")

        assertEquals("uuid-1", history.single().uuid)
        assertEquals("uuid-1", WarnStore(storage, resolveUuid = { uuid }).warnsOf("steve").single().uuid)
    }

    @Test
    fun `a rename does not detach a player from their own warn history`() {
        val store = WarnStore(InMemoryAddonStorage(), resolveUuid = { "uuid-1" })
        store.warn("Steve", "Mod", "spamming")

        assertEquals(1, store.warnsOf("Steve2", uuid = "uuid-1").size)
    }

    @Test
    fun `an unrelated player taking the freed name does not see another uuid's warnings`() {
        val store = WarnStore(InMemoryAddonStorage(), resolveUuid = { "uuid-1" })
        store.warn("Steve", "Mod", "spamming")

        assertTrue(store.warnsOf("Steve", uuid = "uuid-2").isEmpty())
    }

    @Test
    fun `a write upgrades the persisted document to the enveloped schema format`() {
        val storage = InMemoryAddonStorage()
        storage.write(
            "warns",
            """[{"player":"steve","by":"Mod","reason":"legacy","atEpochMs":1}]""",
        )
        val store = WarnStore(storage, clock = { 2_000L })

        store.warn("Alex", "Mod", "griefing")

        assertTrue(storage.read("warns")!!.contains("\"schemaVersion\""))
    }

    @Test
    fun `warns expire out of the active list after the configured window`() {
        var now = 0L
        val store = WarnStore(InMemoryAddonStorage(), expiryMillis = { 60_000 }, clock = { now })

        store.warn("steve", "Mod", "griefing")
        assertEquals(1, store.warnsOf("steve").size)

        now = 30_000
        assertEquals(1, store.warnsOf("Steve").size, "still within the expiry window")

        now = 60_001
        assertTrue(store.warnsOf("STEVE").isEmpty(), "past the expiry window")
    }

    @Test
    fun `newest warns come first and old ones do not hide new ones`() {
        var now = 0L
        val store = WarnStore(InMemoryAddonStorage(), expiryMillis = { 1_000 }, clock = { now })

        store.warn("steve", "Mod", "first")
        now = 2_000
        store.warn("steve", "Mod", "second")

        val active = store.warnsOf("steve")
        assertEquals(listOf("second"), active.map { it.reason })
    }
}
