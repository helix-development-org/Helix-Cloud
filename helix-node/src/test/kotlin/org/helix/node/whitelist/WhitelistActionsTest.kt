package org.helix.node.whitelist

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionSource
import org.helix.node.actions.ActionRegistry

class WhitelistActionsTest {
    private val store = WhitelistStore(createTempDirectory("whitelist").resolve("whitelist.json"))
    private val registry = ActionRegistry().also { WhitelistActions(store).registerAll(it) }

    private fun invoke(action: String, vararg args: String) =
        registry.invoke(ActionInvocation(action, args.toList(), ActionSource.CLI))

    @Test
    fun `mode toggles and reports state`() {
        assertEquals("whitelist: off", invoke("whitelist.mode").lines.first())

        assertTrue(invoke("whitelist.mode", "on").success)
        assertTrue(store.isEnabled())
        assertEquals("whitelist: on", invoke("whitelist.mode").lines.first())

        assertTrue(invoke("whitelist.mode", "off").success)
        assertFalse(store.isEnabled())
    }

    @Test
    fun `add, list and remove round trip`() {
        assertTrue(invoke("whitelist.add", "steve").success)
        assertFalse(invoke("whitelist.add", "steve").success)
        assertEquals(listOf("steve"), invoke("whitelist.list").lines)

        assertTrue(invoke("whitelist.remove", "steve").success)
        assertFalse(invoke("whitelist.remove", "steve").success)
        assertEquals("whitelist is empty", invoke("whitelist.list").lines.first())
    }
}
