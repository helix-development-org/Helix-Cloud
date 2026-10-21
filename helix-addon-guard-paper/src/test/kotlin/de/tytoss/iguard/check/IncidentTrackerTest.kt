package de.tytoss.iguard.check

import de.tytoss.iguard.PLAYER
import de.tytoss.iguard.WORLD
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.model.EvidenceFamily
import de.tytoss.iguard.model.SwingFrame
import de.tytoss.iguard.notify.NotificationService
import de.tytoss.iguard.storage.MemoryGuardStore
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncidentTrackerTest {
    private val config = IGuardConfig.defaults("test")
    private val dynamic = AtomicReference(config.dynamic)
    private val store = MemoryGuardStore()
    private val tracker = IncidentTracker(
        "test", store, config.detection, config.sanctions, dynamic, Enforcement { _, _, _, _, _ -> },
        NotificationService(dynamic, Logger.getLogger("test")),
    )
    private var sequence = 0L

    private fun frame(at: Long) = SwingFrame(PLAYER, ++sequence, at, "V_1_21_11")

    @Test
    fun `sub-threshold evidence opens no incident`() {
        val assignment = tracker.assign(frame(1_000), "Tester", CheckFailure("movement.speed.a", 0.3, emptyMap()), evidenceWeight = 0.2, worldId = WORLD)

        assertNull(assignment)
        assertNull(tracker.latest(PLAYER))
        assertTrue(store.incidents.isEmpty())
    }

    @Test
    fun `sub-threshold evidence only adds proportional confidence to an open incident`() {
        val now = System.currentTimeMillis()
        tracker.assign(frame(now), "Tester", CheckFailure("movement.speed.a", 1.0, emptyMap()), 1.0, WORLD)
        val weak = assertNotNull(tracker.assign(frame(now + 100), "Tester", CheckFailure("combat.reach.a", 0.2, emptyMap()), 0.25, WORLD))
        val full = config.dynamic.confidence.signal.getValue("movement.speed.a")
        val reach = config.dynamic.confidence.signal.getValue("combat.reach.a") * 0.25
        val expected = 1.0 - (1.0 - full) * (1.0 - reach)

        assertEquals(expected, weak.confidence, 1e-9)
        assertEquals(setOf(EvidenceFamily.MOVEMENT, EvidenceFamily.COMBAT), weak.incident.families)
    }

    @Test
    fun `incident upserts are throttled to meaningful changes`() {
        val start = 5_000L
        repeat(20) { index ->
            tracker.assign(frame(start + index * 100L), "Tester", CheckFailure("movement.fly.a", 1.0, emptyMap()), 1.0, WORLD)
        }
        assertEquals(1, store.incidents.size, "same family, same confidence: one write")

        tracker.assign(frame(start + 2_100), "Tester", CheckFailure("combat.reach.a", 1.0, emptyMap()), 1.0, WORLD)
        assertEquals(2, store.incidents.size, "a new evidence family is written at once")

        tracker.assign(frame(start + 7_200), "Tester", CheckFailure("combat.reach.a", 1.0, emptyMap()), 1.0, WORLD)
        assertEquals(3, store.incidents.size, "refreshed at most every five seconds while growing")
        assertEquals(WORLD, store.incidents.last().worldId)
        assertEquals(22, store.incidents.last().evidenceCount)
    }

    @Test
    fun `a throttled final state is flushed when the player leaves`() {
        tracker.assign(frame(1_000), "Tester", CheckFailure("movement.fly.a", 1.0, emptyMap()), 1.0, WORLD)
        tracker.assign(frame(1_100), "Tester", CheckFailure("movement.fly.a", 1.0, emptyMap()), 1.0, WORLD)
        assertEquals(1, store.incidents.size)

        tracker.remove(PLAYER)
        tracker.shutdown()
        assertEquals(2, store.incidents.count { it.incidentId == store.incidents.first().incidentId })
        assertEquals(2, store.incidents.last().evidenceCount)
    }
}
