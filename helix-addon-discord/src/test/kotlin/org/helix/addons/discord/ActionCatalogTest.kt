package org.helix.addons.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult

class ActionCatalogTest {
    private val descriptors = listOf(
        ActionDescriptor("service.list", "lists", "service.list"),
        ActionDescriptor("service.start", "starts", "service.start <task>"),
        ActionDescriptor("ban.set", "bans", "ban.set <player> <issuedBy> [duration] [reason...]"),
        ActionDescriptor("kick", "kicks", "kick <player>", playerCommand = true),
    )
    private val catalog = ActionCatalog(object : ActionInvoker {
        override fun invoke(invocation: ActionInvocation): ActionResult = ActionResult.ok()

        override fun descriptors(): List<ActionDescriptor> = descriptors
    })

    @Test
    fun `actions group by prefix with player commands under commands`() {
        assertEquals(listOf("ban", "commands", "service"), catalog.groups())
        assertEquals(listOf("service.list", "service.start"), catalog.actionsIn("service").map { it.name })
        assertEquals(listOf("kick"), catalog.actionsIn("commands").map { it.name })
    }

    @Test
    fun `find and argument hints`() {
        assertEquals("bans", catalog.find("ban.set")?.description)
        assertNull(catalog.find("nope"))
        assertEquals("", catalog.argumentHint(catalog.find("service.list")!!))
        assertEquals("<task>", catalog.argumentHint(catalog.find("service.start")!!))
    }

    @Test
    fun `cloud lines parse services and tasks`() {
        val services = CloudLines.services(
            listOf(
                "Lobby-1 [RUNNING] port=25601 players=3/64 executor=java",
                "no services",
            ),
        )
        assertEquals(1, services.size)
        assertEquals("Lobby-1", services[0].id)
        assertEquals("RUNNING", services[0].state)
        assertEquals("3/64", services[0].players)

        val tasks = CloudLines.taskNames(
            listOf(
                "Lobby [PAPER 1.21.11] executor=java services=1/2 static=false",
                "no tasks configured — create one with task.create",
            ),
        )
        assertEquals(listOf("Lobby"), tasks)
    }
}
