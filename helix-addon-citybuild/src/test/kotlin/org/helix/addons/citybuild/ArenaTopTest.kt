package org.helix.addons.citybuild

import org.helix.addons.citybuild.service.ArenaRank
import org.helix.addons.citybuild.service.ProfileService
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaTopTest {
    @Test
    fun `parses stats top lines and skips garbage`() {
        val rows = ProfileService.parseTop(listOf("#1 Steve - 42", "#2 Alex Two - 7", "nonsense"))
        assertEquals(listOf(ArenaRank(1, "Steve", 42), ArenaRank(2, "Alex Two", 7)), rows)
    }

    @Test
    fun `arena top asks the stats addon for kills`() {
        val calls = mutableListOf<ActionInvocation>()
        val invoker = object : ActionInvoker {
            override fun invoke(invocation: ActionInvocation): ActionResult {
                calls += invocation
                return ActionResult.ok("#1 Steve - 3")
            }

            override fun descriptors(): List<ActionDescriptor> = emptyList()
        }
        val service = ProfileService(testContext(), invoker, { it }, { it }, { _, key -> key })
        assertEquals(listOf(ArenaRank(1, "Steve", 3)), service.arenaTop(5))
        assertEquals("stats.top", calls.single().action)
        assertEquals(listOf(ProfileService.KILLS, "5"), calls.single().arguments)
    }

    @Test
    fun `missing stats addon yields an empty board`() {
        val invoker = object : ActionInvoker {
            override fun invoke(invocation: ActionInvocation): ActionResult = ActionResult.error("unknown action")

            override fun descriptors(): List<ActionDescriptor> = emptyList()
        }
        assertTrue(ProfileService(testContext(), invoker, { it }, { it }, { _, key -> key }).arenaTop(5).isEmpty())
    }
}
