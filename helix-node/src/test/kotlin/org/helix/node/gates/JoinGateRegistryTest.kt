package org.helix.node.gates

import org.helix.api.proxy.JoinDecision
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.ProxyCommand
import org.helix.node.proxy.ProxyCommandQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoinGateRegistryTest {
    private val registry = JoinGateRegistry()

    @Test
    fun `no gates allow everyone`() {
        assertTrue(registry.evaluate(JoinRequest("steve")).allowed)
    }

    @Test
    fun `first denial wins`() {
        registry.register("a") { JoinDecision.allow() }
        registry.register("b") { JoinDecision.deny("banned") }

        val decision = registry.evaluate(JoinRequest("steve"))

        assertFalse(decision.allowed)
        assertEquals("banned", decision.message)
    }

    @Test
    fun `unregistering an owner removes its gates`() {
        registry.register("bans") { JoinDecision.deny("banned") }
        registry.unregisterOwner("bans")

        assertTrue(registry.evaluate(JoinRequest("steve")).allowed)
    }

    @Test
    fun `throwing gate fails open`() {
        registry.register("broken") { error("boom") }

        assertTrue(registry.evaluate(JoinRequest("steve")).allowed)
    }

    @Test
    fun `command queue delivers per proxy exactly once`() {
        val queue = ProxyCommandQueue()
        queue.enqueue(listOf("Proxy-1", "Proxy-2"), ProxyCommand.kick("steve", "banned"))

        assertEquals(listOf(ProxyCommand.kick("steve", "banned")), queue.drain("Proxy-1"))
        assertTrue(queue.drain("Proxy-1").isEmpty())
        assertEquals(1, queue.drain("Proxy-2").size)
        assertTrue(queue.drain("Proxy-3").isEmpty())
    }
}
