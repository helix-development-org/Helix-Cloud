package org.helix.addons.guardlab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardLabAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("guardlab"))

    init {
        GuardLabAddon(Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC)).onEnable(context)
    }

    private fun call(action: String, vararg args: String, source: ActionSource = ActionSource.CLI): ActionResult =
        context.handlers.getValue(action).second.execute(ActionInvocation(action, args.toList(), source))

    @Test
    fun `publishes closed default settings on enable`() {
        val settings = Json.parseToJsonElement(context.bridgeValues.getValue(GuardLabAddon.SETTINGS_KEY)).jsonObject
        assertEquals("", settings["token"]!!.jsonPrimitive.content)
        assertEquals("all", settings["suite"]!!.jsonPrimitive.content)
        assertTrue(settings["autostart"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `configure validates and publishes token and suite`() {
        assertFalse(call("guardlab.configure", "short").success)
        assertFalse(call("guardlab.configure", "a".repeat(20), "Bad Suite").success)
        val ok = call("guardlab.configure", "tok_0123456789abcdef", "cheats", "false")
        assertTrue(ok.success, ok.lines.joinToString())
        val settings = Json.parseToJsonElement(context.bridgeValues.getValue(GuardLabAddon.SETTINGS_KEY)).jsonObject
        assertEquals("tok_0123456789abcdef", settings["token"]!!.jsonPrimitive.content)
        assertEquals("cheats", settings["suite"]!!.jsonPrimitive.content)
        assertFalse(settings["autostart"]!!.jsonPrimitive.boolean)
        // "-" keeps the token
        assertTrue(call("guardlab.configure", "-", "legit").success)
        val again = Json.parseToJsonElement(context.bridgeValues.getValue(GuardLabAddon.SETTINGS_KEY)).jsonObject
        assertEquals("tok_0123456789abcdef", again["token"]!!.jsonPrimitive.content)
        assertTrue(call("guardlab.status").lines.first().contains("set (20 chars)"))
    }

    @Test
    fun `stores summaries newest first and only from the test task`() {
        val descriptor = context.handlers.getValue("guardlab.report.store").first
        assertTrue(descriptor.bridgeInvocable)
        assertEquals(setOf(GuardLabAddon.TEST_TASK), descriptor.bridgeTasks)
        assertFalse(context.handlers.getValue("guardlab.configure").first.bridgeInvocable)
        assertTrue(call("guardlab.report.store", """{"suite":"legit","passed":3,"failed":1}""", source = ActionSource.BRIDGE).success)
        assertTrue(call("guardlab.report.store", """{"suite":"cheats","passed":5}""", source = ActionSource.BRIDGE).success)
        assertFalse(call("guardlab.report.store", "{not json", source = ActionSource.BRIDGE).success)
        val list = Json.parseToJsonElement(call("guardlab.reports").lines.single()).jsonArray
        assertEquals(listOf("cheats", "legit"), list.map { it.jsonObject["suite"]!!.jsonPrimitive.content })
    }

    @Test
    fun `run publishes a request with a fresh nonce`() {
        assertFalse(call("guardlab.run").success)
        assertTrue(call("guardlab.run", "lag", "GuardLab").success)
        val request = Json.parseToJsonElement(context.bridgeValues.getValue(GuardLabAddon.REQUEST_KEY)).jsonObject
        assertEquals("lag", request["suite"]!!.jsonPrimitive.content)
        assertEquals("GuardLab", request["player"]!!.jsonPrimitive.content)
        val first = request["nonce"]!!.jsonPrimitive.content
        call("guardlab.run", "lag")
        val second = Json.parseToJsonElement(context.bridgeValues.getValue(GuardLabAddon.REQUEST_KEY)).jsonObject["nonce"]!!.jsonPrimitive.content
        assertTrue(first != second)
    }
}
