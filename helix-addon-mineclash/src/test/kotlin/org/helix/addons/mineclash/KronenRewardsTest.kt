package org.helix.addons.mineclash

import org.helix.addons.mineclash.profile.KronenRewards
import org.helix.addons.mineclash.profile.MatchRecord
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class KronenRewardsTest {
    private class FakeEconomy(private val available: Boolean = true) : ActionInvoker {
        val calls = mutableListOf<List<String>>()
        override fun invoke(invocation: ActionInvocation): ActionResult {
            if (!available || invocation.action != "eco.api.deposit") return ActionResult.error("unknown action")
            calls += invocation.arguments
            return ActionResult.ok("1000")
        }
        override fun descriptors(): List<ActionDescriptor> = emptyList()
    }

    @Test
    fun `every player gets the match share and winners the bonus`() {
        val eco = FakeEconomy()
        val paid = KronenRewards(eco, { 10 }, { 40 })
            .pay(MatchRecord("2V2", winnerTeam = 1, players = listOf(listOf("a", "b"), listOf("c", "d"))))
        assertEquals(120, paid)
        assertEquals(
            listOf(
                listOf("a", "10", "mineclash", "match:2V2"), listOf("b", "10", "mineclash", "match:2V2"),
                listOf("c", "50", "mineclash", "match:2V2"), listOf("d", "50", "mineclash", "match:2V2"),
            ),
            eco.calls,
        )
    }

    @Test
    fun `draws pay only the match share`() {
        val eco = FakeEconomy()
        assertEquals(20, KronenRewards(eco, { 10 }, { 40 }).pay(MatchRecord("1V1", -1, listOf(listOf("a"), listOf("b")))))
    }

    @Test
    fun `zero or negative settings pay nothing`() {
        val eco = FakeEconomy()
        assertEquals(0, KronenRewards(eco, { 0 }, { -5 }).pay(MatchRecord("1V1", 0, listOf(listOf("a"), listOf("b")))))
        assertEquals(emptyList(), eco.calls)
    }

    @Test
    fun `without the economy addon nothing is paid`() {
        assertEquals(0, KronenRewards(FakeEconomy(available = false), { 10 }, { 40 }).pay(MatchRecord("1V1", 0, listOf(listOf("a")))))
    }
}
