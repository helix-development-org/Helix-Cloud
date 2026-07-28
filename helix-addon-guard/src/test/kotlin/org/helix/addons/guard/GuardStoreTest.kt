package org.helix.addons.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.storage.InMemoryAddonStorage

class GuardStoreTest {
    @Test
    fun `violations older than the retention window are pruned on write`() {
        var now = 0L
        val storage = InMemoryAddonStorage()
        val store = GuardStore(storage, violationRetentionDays = { 1 }, clock = { now })

        store.addViolation(violation(now))
        now = 2 * 86_400_000L
        store.addViolation(violation(now))

        assertEquals(listOf(now), store.violations("uuid-1", limit = 10).map { it.epochMs })
    }

    @Test
    fun `replay payloads are pruned by retention and the document itself is deleted`() {
        var now = 0L
        val storage = InMemoryAddonStorage()
        val store = GuardStore(storage, replayRetentionDays = { 1 }, clock = { now })

        store.writeReplay("old-incident", "payload-old")
        assertEquals("payload-old", store.replay("old-incident"))

        now = 2 * 86_400_000L
        store.writeReplay("new-incident", "payload-new")

        assertNull(store.replay("old-incident"), "expired replay payload document must be deleted, not just unindexed")
        assertEquals("payload-new", store.replay("new-incident"))
    }

    @Test
    fun `replay index enforces a hard count cap even with generous retention`() {
        val storage = InMemoryAddonStorage()
        val store = GuardStore(storage, replayRetentionDays = { 3650 })

        repeat(REPLAY_INDEX_CAP_FOR_TEST + 5) { index -> store.writeReplay("incident-$index", "payload-$index") }

        assertNull(store.replay("incident-0"), "oldest replay beyond the hard cap must be evicted")
        assertEquals("payload-${REPLAY_INDEX_CAP_FOR_TEST + 4}", store.replay("incident-${REPLAY_INDEX_CAP_FOR_TEST + 4}"))
    }

    private fun violation(epochMs: Long) = GuardViolation(
        serverId = "lobby",
        uuid = "uuid-1",
        name = "steve",
        check = "movement.fly.a",
        vl = 1.0,
        confidence = 0.6,
        epochMs = epochMs,
        details = "{}",
    )

    private companion object {
        /** Mirrors GuardStore's private REPLAY_INDEX_CAP so the hard-cap test does not hardcode a magic number. */
        const val REPLAY_INDEX_CAP_FOR_TEST = 500
    }
}
