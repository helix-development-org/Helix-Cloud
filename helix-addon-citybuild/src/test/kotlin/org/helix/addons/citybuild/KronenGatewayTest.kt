package org.helix.addons.citybuild

import org.helix.addons.citybuild.economy.ActionKronenGateway
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KronenGatewayTest {
    private val calls = mutableListOf<ActionInvocation>()
    private var answer: ActionResult = ActionResult.ok("42")
    private val invoker = object : ActionInvoker {
        override fun invoke(invocation: ActionInvocation): ActionResult {
            calls += invocation
            return answer
        }
        override fun descriptors(): List<ActionDescriptor> = emptyList()
    }
    private val gateway = ActionKronenGateway(invoker)

    @Test
    fun `calls the economy machine api with positional arguments`() {
        val deposit = gateway.deposit(ALICE, 12, "job:miner", "jobs:2026-10-16", "citybuild.jobs", 4000)
        assertTrue(deposit.ok)
        assertEquals(42, deposit.balance)
        gateway.withdraw(ALICE, 5, "adminshop.buy", "ware:stone")
        gateway.transfer(ALICE, BOB, 100, "auction.sale", 50, "auction:1")
        assertEquals(
            listOf(
                "eco.api.deposit" to listOf(ALICE, "12", "job:miner", "jobs:2026-10-16", "citybuild.jobs", "4000"),
                "eco.api.withdraw" to listOf(ALICE, "5", "adminshop.buy", "ware:stone"),
                "eco.api.transfer" to listOf(ALICE, BOB, "100", "auction.sale", "50", "auction:1"),
            ),
            calls.map { it.action to it.arguments },
        )
        assertTrue(gateway.deposit(ALICE, 0, "x", "y").ok)
        assertEquals(3, calls.size, "zero amounts are not booked")
    }

    @Test
    fun `maps economy failures to codes`() {
        answer = ActionResult.error("insufficient funds")
        assertEquals("insufficient_funds", gateway.withdraw(ALICE, 5, "r", "x").code)
        answer = ActionResult.error("daily limit reached")
        assertEquals("limit", gateway.deposit(ALICE, 5, "r", "x").code)
        answer = ActionResult.error("unknown or non-bridge action: eco.api.transfer")
        assertEquals("economy_unavailable", gateway.transfer(ALICE, BOB, 5, "r", 0, "x").code)
        answer = ActionResult.error("boom")
        assertFalse(gateway.withdraw(ALICE, 5, "r", "x").ok)
    }
}
