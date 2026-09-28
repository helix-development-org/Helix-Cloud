package org.helix.addons.mineclash

import org.helix.addons.mineclash.profile.MatchRecord
import org.helix.addons.mineclash.profile.StatsReporter
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsReporterTest {
    private class FakeActions(private val available: Boolean = true) : ActionInvoker {
        val calls = mutableListOf<List<String>>()
        override fun invoke(invocation: ActionInvocation): ActionResult {
            if (!available || invocation.action != "stats.add") return ActionResult.error("unknown action")
            calls += invocation.arguments
            return ActionResult.ok("1")
        }
        override fun descriptors(): List<ActionDescriptor> = emptyList()
    }

    private val names = mapOf("u1" to "Alice", "u2" to "Bob")

    @Test
    fun `wins losses matches and roses per player`() {
        val actions = FakeActions()
        val accepted = StatsReporter(actions, names::get)
            .report(MatchRecord("1V1", winnerTeam = 0, players = listOf(listOf("u1"), listOf("u2")), rosesDelta = mapOf("u1" to 30, "u2" to -20)))
        assertEquals(6, accepted)
        assertEquals(
            listOf(
                listOf("mineclash.matches", "Alice", "1"), listOf("mineclash.wins", "Alice", "1"), listOf("mineclash.roses", "Alice", "30"),
                listOf("mineclash.matches", "Bob", "1"), listOf("mineclash.losses", "Bob", "1"), listOf("mineclash.roses", "Bob", "-20"),
            ),
            actions.calls,
        )
    }

    @Test
    fun `draws count as match only and unknown players are skipped`() {
        val actions = FakeActions()
        StatsReporter(actions, names::get).report(MatchRecord("1V1", winnerTeam = -1, players = listOf(listOf("u1"), listOf("ghost"))))
        assertEquals(listOf(listOf("mineclash.matches", "Alice", "1")), actions.calls)
    }

    @Test
    fun `without the stats addon nothing is accepted`() {
        assertEquals(0, StatsReporter(FakeActions(available = false), names::get).report(MatchRecord("1V1", 0, listOf(listOf("u1")))))
    }
}
