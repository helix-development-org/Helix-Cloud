package org.helix.addons.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionInvocation
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("admin"))
    private val addon = AdminAddon().also { it.onEnable(context) }

    private fun report(service: String, vararg names: String) =
        """{"service":"$service","tps":19.5,"players":[${names.joinToString(",") { """{"name":"$it","x":1,"y":2,"z":3}""" }}]}"""

    @Test
    fun `presence resolves which service a player is on`() {
        assertTrue(context.run("admin.presence", report("Game-1", "Alice", "Bob")).success)
        assertTrue(context.run("admin.presence", report("Lobby-1", "Carl")).success)
        assertEquals("Game-1", context.run("admin.where", "alice").lines.single())
        assertFalse(context.run("admin.where", "Nobody").success)

        val players = Json.parseToJsonElement(context.run("admin.players").lines.single()).jsonArray
        assertEquals(listOf("Alice", "Bob", "Carl"), players.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("Lobby-1", players.last().jsonObject["service"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jobs are routed to the service hosting the target`() {
        context.run("admin.presence", report("Game-1", "Alice"))
        assertEquals(0L, Json.parseToJsonElement(context.run("admin.jobs", "Game-1", "-1").lines.single()).jsonObject["seq"]!!.jsonPrimitive.content.toLong())
        assertTrue(context.run("admin.job", "Alice", "freeze", """{"player":"Alice"}""").success)
        assertTrue(context.run("admin.job", "@Lobby-1", "arrive", """{"player":"Alice","to":"Carl"}""").success)
        assertFalse(context.run("admin.job", "Ghost", "heal", "{}").success)

        val batch = Json.parseToJsonElement(context.run("admin.jobs", "Game-1", "0").lines.single()).jsonObject
        assertEquals(listOf("freeze"), batch["jobs"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        val lobby = Json.parseToJsonElement(context.run("admin.jobs", "Lobby-1", "0").lines.single()).jsonObject
        assertEquals(listOf("arrive"), lobby["jobs"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `machine api is bridge invocable, staff actions need helix staff permissions`() {
        listOf("admin.presence", "admin.where", "admin.job", "admin.jobs").forEach {
            assertTrue(context.handlers.getValue(it).first.bridgeInvocable, it)
        }
        val staff = mapOf(
            "admin.players" to "helix.staff.menu", "admin.kick" to "helix.staff.kick", "admin.warn" to "helix.staff.warn",
            "admin.ban" to "helix.staff.ban", "admin.message" to "helix.staff.message", "admin.announce" to "helix.staff.announce",
            "admin.service.restart" to "helix.staff.server",
        )
        staff.forEach { (action, node) ->
            val descriptor = context.handlers.getValue(action).first
            assertEquals(node, descriptor.permission, action)
            assertFalse(descriptor.bridgeInvocable, action)
        }
        assertNull(context.handlers.getValue("admin.panelurl").first.permission, "panel URL is admin-only")
    }

    @Test
    fun `moderation actions are attributed to the signed-in session`() {
        val calls = mutableListOf<ActionInvocation>()
        context.handlers["ban.set"] = context.handlers.getValue("admin.players").first.copy(name = "ban.set", permission = null) to
            org.helix.api.action.ActionHandler { calls += it; org.helix.api.action.ActionResult.ok("banned") }
        val result = context.actions.invoke(ActionInvocation("admin.ban", listOf("Griefer", "7d", "x-ray"), actor = "ModAnna"))
        assertTrue(result.success)
        assertEquals(listOf("Griefer", "ModAnna", "7d", "x-ray"), calls.single().arguments)
        assertEquals("ModAnna", calls.single().actor)
    }

    @Test
    fun `panel url is validated and published`() {
        assertFalse(context.run("admin.panelurl", "ftp://x").success)
        assertTrue(context.run("admin.panelurl", "127.0.0.1").success, "the host alone is enough")
        assertTrue(context.run("admin.panelurl", "https://panel.example.net/").success)
        assertEquals("https://panel.example.net", context.bridgeValues["admin.panel_url"])
        assertTrue(context.run("admin.panelurl", "-").success)
        assertNull(context.bridgeValues["admin.panel_url"])
    }

    @Test
    fun `job queue expires old jobs and a fresh poller only gets the cursor`() {
        var now = 0L
        val queue = JobQueue(ttlMs = 1_000, clock = { now })
        queue.push("S", "heal", JsonObject(mapOf("player" to JsonPrimitive("A"))))
        assertTrue(queue.poll("S", -1).jobs.isEmpty(), "restarted service does not replay")
        assertEquals(1, queue.poll("S", 0).jobs.size)
        now = 2_000
        assertTrue(queue.poll("S", 0).jobs.isEmpty(), "expired")
    }

    @Test
    fun `the staff relay checks the target action's permission for the in-game player`() {
        context.permissionCheck = { player, permission -> player == "Mod" && permission == "helix.staff.menu" }
        assertTrue(context.handlers.getValue("admin.staff").first.bridgeInvocable)
        assertTrue(context.run("admin.staff", "Mod", "admin.players").success)
        assertFalse(context.run("admin.staff", "Mod", "admin.kick", "Griefer").success, "kick needs helix.staff.kick")
        assertFalse(context.run("admin.staff", "Guest", "admin.players").success)
        assertFalse(context.run("admin.staff", "Mod", "admin.panelurl", "x").success, "only staff actions are relayed")
    }
}
