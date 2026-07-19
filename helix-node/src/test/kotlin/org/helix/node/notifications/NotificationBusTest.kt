package org.helix.node.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationBusTest {
    private val bus = NotificationBus()

    @Test
    fun `publish reaches all listeners`() {
        val received = mutableListOf<String>()
        bus.register("a") { category, message -> received += "$category:$message" }
        bus.register("b") { _, message -> received += message }

        bus.publish("moderation", "banned")

        assertEquals(listOf("moderation:banned", "banned"), received)
    }

    @Test
    fun `unregistering an owner removes its listeners`() {
        val received = mutableListOf<String>()
        bus.register("team") { _, message -> received += message }
        bus.unregisterOwner("team")

        bus.publish("moderation", "banned")

        assertTrue(received.isEmpty())
    }

    @Test
    fun `throwing listener does not break the others`() {
        val received = mutableListOf<String>()
        bus.register("broken") { _, _ -> error("boom") }
        bus.register("ok") { _, message -> received += message }

        bus.publish("moderation", "banned")

        assertEquals(listOf("banned"), received)
    }
}
