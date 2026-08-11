package org.helix.addons.discord

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PermissionGateTest {
    private val links = LinkStore(InMemoryAddonStorage())
    private val granted = mutableSetOf<Pair<String, String>>()
    private val gate = PermissionGate(
        links = links,
        hasPermission = { player, node -> player to node in granted },
        currentName = { uuid -> if (uuid == "uuid-1") "SteveRenamed" else null },
    )

    @Test
    fun `unlinked users are rejected`() {
        assertIs<Access.NotLinked>(gate.forAction("42", "service.stop"))
        assertIs<Access.NotLinked>(gate.forNode("42", PermissionGate.SETUP_NODE))
    }

    @Test
    fun `per action node is checked against the current name after rename`() {
        links.setLink("42", "uuid-1", "Steve", "admin")

        val denied = assertIs<Access.Denied>(gate.forAction("42", "service.stop"))
        assertEquals("helix.discord.action.service.stop", denied.node)

        granted += "SteveRenamed" to "helix.discord.action.service.stop"
        val access = assertIs<Access.Granted>(gate.forAction("42", "service.stop"))
        assertEquals("SteveRenamed", access.actorName)
    }

    @Test
    fun `a descriptor permission is required in addition`() {
        links.setLink("42", "uuid-1", "Steve", "admin")
        granted += "SteveRenamed" to "helix.discord.action.kick"

        val denied = assertIs<Access.Denied>(gate.forAction("42", "kick", "helix.mod.kick"))
        assertEquals("helix.mod.kick", denied.node)

        granted += "SteveRenamed" to "helix.mod.kick"
        assertIs<Access.Granted>(gate.forAction("42", "kick", "helix.mod.kick"))
    }
}
