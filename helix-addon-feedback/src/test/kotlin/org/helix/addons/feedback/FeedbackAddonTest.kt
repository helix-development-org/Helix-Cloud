package org.helix.addons.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("feedback"))
    private val clock = TestClock()

    init {
        context.invocationResult = { inv ->
            if (inv.action == "service.logs") ActionResult.ok("[09:59:00 ERROR]: boom", "[09:59:30 INFO]: ok") else ActionResult.ok()
        }
        context.permissionCheck = { _, perm -> perm == "feedback.report" }
        FeedbackAddon(clock = clock).onEnable(context)
        listOf("alice", "bob", "carl").forEach { call("feedback.agreement.accept", "uuid-$it") }
    }

    private fun call(action: String, vararg args: String, source: ActionSource = ActionSource.BRIDGE): ActionResult =
        context.handlers.getValue(action).second.execute(ActionInvocation(action, args.toList(), source))

    private fun create(name: String, severity: String = "annoying"): Long {
        val body = """{"reporter":{"uuid":"uuid-$name","name":"$name"},"severity":"$severity","category":"lobby","title":"Bug von $name",""" +
            """"description":"kaputt","context":{"service":"Lobby-1","serverTime":"10:00:00"}}"""
        val r = call("feedback.create", body)
        assertTrue(r.success, r.lines.joinToString())
        return Json.parseToJsonElement(r.lines[0]).jsonObject["nr"]!!.jsonPrimitive.long
    }

    @Test
    fun `registers bridge actions and the panel`() {
        val bridge = context.handlers.filterValues { it.first.bridgeInvocable }.keys
        assertTrue("feedback.create" in bridge && "feedback.attach.chunk" in bridge && "feedback.mine" in bridge)
        assertFalse(context.handlers.getValue("feedback.settings").first.bridgeInvocable)
        assertFalse(context.handlers.getValue("feedback.attachment").first.bridgeInvocable)
    }

    @Test
    fun `bridge reports carry the service log excerpt and notify discord`() {
        val nr = create("alice")
        val details = Json.parseToJsonElement(call("feedback.get", nr.toString(), """{"uuid":"uuid-alice","name":"alice"}""").lines[0]).jsonObject
        val logs = details["report"]!!.jsonObject["context"]!!.jsonObject["logs"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("[09:59:00 ERROR]: boom"), logs)
        assertTrue(context.invocations.any { it.action == "service.logs" && it.arguments.first() == "Lobby-1" })
        assertTrue(context.notifications.any { it.first == "bugreport" && it.second.contains("#$nr") })
    }

    @Test
    fun `staff workflow is dashboard only and bridge callers are never staff`() {
        val nr = create("alice")
        for (action in listOf("feedback.status", "feedback.assign", "feedback.duplicate", "feedback.severity", "feedback.list", "feedback.toollog.list")) {
            assertFalse(context.handlers.getValue(action).first.bridgeInvocable, action)
        }
        context.permissionCheck = { player, perm -> perm == "feedback.report" || (player == "mod" && perm == "feedback.staff") }
        val viaBridge = call("feedback.status", """{"nr":$nr,"status":"confirmed","actor":{"uuid":"uuid-mod","name":"mod"}}""")
        assertEquals("forbidden", viaBridge.lines[0], "a payload name never grants staff rights over the bridge")
        assertEquals("forbidden", call("feedback.get", nr.toString(), """{"uuid":"uuid-mod","name":"mod"}""").lines[0])
        val panel = call("feedback.status", """{"nr":$nr,"status":"confirmed"}""", source = ActionSource.REST)
        assertTrue(panel.success, panel.lines.joinToString())
        val top = Json.parseToJsonElement(call("feedback.top").lines[0]).jsonArray
        assertEquals(2, top.single().jsonObject["points"]!!.jsonPrimitive.int)
        val notes = Json.parseToJsonElement(call("feedback.notifications", "uuid-alice").lines[0]).jsonArray
        assertEquals(1, notes.size)
        val reclassified = call("feedback.severity", nr.toString(), "blocking", source = ActionSource.REST)
        assertTrue(reclassified.success, reclassified.lines.joinToString())
        assertEquals(4, Json.parseToJsonElement(call("feedback.top").lines[0]).jsonArray.single().jsonObject["points"]!!.jsonPrimitive.int)
        assertTrue(context.notifications.any { it.first == FeedbackEvent.CATEGORY && it.second.contains("\"reclassified\"") })
    }

    @Test
    fun `reporting needs the tester permission and the agreement`() {
        context.permissionCheck = { _, _ -> false }
        val body = """{"reporter":{"uuid":"uuid-dora","name":"dora"},"severity":"minor","category":"other","title":"x","description":"y"}"""
        assertEquals("not_tester", call("feedback.create", body).lines[0])
        context.permissionCheck = { _, perm -> perm == "feedback.report" }
        assertEquals("agreement_required", call("feedback.create", body).lines[0])
        call("feedback.agreement.accept", "uuid-dora")
        assertTrue(call("feedback.create", body).success)
    }

    @Test
    fun `dashboard calls are staff and see every report`() {
        create("alice", "exploit")
        clock.advanceSeconds(61)
        create("bob")
        val asBob = Json.parseToJsonElement(call("feedback.list", """{"viewer":{"uuid":"uuid-bob","name":"bob"}}""").lines[0]).jsonObject
        assertEquals(1, asBob["total"]!!.jsonPrimitive.int)
        val panel = Json.parseToJsonElement(call("feedback.list", source = ActionSource.REST).lines[0]).jsonObject
        assertEquals(2, panel["total"]!!.jsonPrimitive.int)
        assertEquals(0, Json.parseToJsonElement(call("feedback.list").lines[0]).jsonObject["total"]!!.jsonPrimitive.int, "no viewer, no reports")
    }

    private fun panel(action: String, actor: String, vararg args: String): ActionResult =
        context.handlers.getValue(action).second.execute(ActionInvocation(action, args.toList(), ActionSource.REST, actor = actor))

    @Test
    fun `a tester in the dashboard is a player, a team member with feedback staff is staff`() {
        context.uuidsByName["bob"] = "uuid-bob"
        context.uuidsByName["mod"] = "uuid-mod"
        context.permissionCheck = { player, perm -> perm == "feedback.report" || (player == "mod" && perm == "feedback.staff") }
        val alices = create("alice", "exploit")

        assertEquals("forbidden", panel("feedback.get", "bob", alices.toString()).lines[0], "no foreign reports, no exploits")
        val forged = """{"reporter":{"uuid":"uuid-alice","name":"alice"},"severity":"minor","category":"lobby","title":"Panel-Bug",""" +
            """"description":"kaputt","context":{"service":"Lobby-1"}}"""
        val created = panel("feedback.create", "bob", forged)
        assertTrue(created.success, created.lines.joinToString())
        val report = Json.parseToJsonElement(created.lines[0]).jsonObject
        assertEquals("bob", report["reporter"]!!.jsonObject["name"]!!.jsonPrimitive.content, "the payload reporter is ignored")
        assertEquals("panel", report["source"]!!.jsonPrimitive.content)
        assertFalse(report["context"]!!.jsonObject.containsKey("logs"), "no server logs pulled into a panel report")
        val mine = Json.parseToJsonElement(panel("feedback.mine", "bob", "uuid-alice").lines[0]).jsonArray
        assertEquals(listOf("bob"), mine.map { it.jsonObject["reporter"]!!.jsonObject["name"]!!.jsonPrimitive.content }, "only own reports")
        val status = panel("feedback.status", "bob", """{"nr":$alices,"status":"confirmed"}""")
        assertEquals("forbidden", status.lines[0])
        val whoBob = Json.parseToJsonElement(panel("feedback.whoami", "bob").lines[0]).jsonObject
        assertEquals("false", whoBob["staff"]!!.jsonPrimitive.content)

        val staff = panel("feedback.status", "mod", """{"nr":$alices,"status":"confirmed"}""")
        assertTrue(staff.success, staff.lines.joinToString())
        assertTrue(panel("feedback.get", "mod", alices.toString()).success)
        assertEquals("true", Json.parseToJsonElement(panel("feedback.whoami", "mod").lines[0]).jsonObject["staff"]!!.jsonPrimitive.content)
    }

    @Test
    fun `player-facing actions need feedback report, the workflow feedback staff`() {
        val declared = context.handlers.mapValues { it.value.first.permission }
        for (action in listOf("feedback.create", "feedback.get", "feedback.comment", "feedback.attach", "feedback.attach.chunk", "feedback.attach.finish", "feedback.mine", "feedback.whoami")) {
            assertEquals("feedback.report", declared[action], action)
        }
        for (action in listOf("feedback.list", "feedback.status", "feedback.assign", "feedback.duplicate", "feedback.severity", "feedback.attachment", "feedback.export", "feedback.toollog.list")) {
            assertEquals("feedback.staff", declared[action], action)
        }
        assertEquals("feedback.admin", declared["feedback.settings"])
    }

    @Test
    fun `chunked attachment upload over actions`() {
        val nr = create("alice")
        val data = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        assertTrue(call("feedback.attach.chunk", "up", "0", "1", data).success)
        val viewer = """{"uuid":"uuid-alice","name":"alice"}"""
        val att = call("feedback.attach.finish", "up", nr.toString(), "shot.jpg", "image/jpeg", viewer)
        assertTrue(att.success, att.lines.joinToString())
        val id = Json.parseToJsonElement(att.lines[0]).jsonObject["id"]!!.jsonPrimitive.content
        val loaded = call("feedback.attachment", id, source = ActionSource.REST)
        assertEquals(listOf("image/jpeg", data), loaded.lines)
    }

    @Test
    fun `agreement and settings round trip`() {
        assertEquals(false, Json.parseToJsonElement(call("feedback.agreement.get", "uuid-x").lines[0]).jsonObject["accepted"]!!.jsonPrimitive.content.toBoolean())
        call("feedback.agreement.accept", "uuid-x")
        assertEquals(true, Json.parseToJsonElement(call("feedback.agreement.get", "uuid-x").lines[0]).jsonObject["accepted"]!!.jsonPrimitive.content.toBoolean())
        val patched = call("feedback.settings", """{"agreementVersion":2}""", source = ActionSource.REST)
        assertTrue(patched.success)
        assertEquals(false, Json.parseToJsonElement(call("feedback.agreement.get", "uuid-x").lines[0]).jsonObject["accepted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("invalid_settings", call("feedback.settings", """{"zone":"Mars/Base"}""", source = ActionSource.REST).lines[0])
    }

    @Test
    fun `settings are published as bridge value and tool use is logged`() {
        val published = Json.parseToJsonElement(context.bridgeValues.getValue("feedback.settings")).jsonObject
        assertEquals("true", published["testphase"]!!.jsonPrimitive.content)
        assertFalse(published.containsKey("rewards"))
        call("feedback.settings", """{"testphase":false}""", source = ActionSource.REST)
        assertEquals("false", Json.parseToJsonElement(context.bridgeValues.getValue("feedback.settings")).jsonObject["testphase"]!!.jsonPrimitive.content)
        val logged = call("feedback.toollog", "uuid-alice", """{"name":"alice","action":"kronen","details":{"amount":500},"service":"CityBuild-1"}""")
        assertTrue(logged.success, logged.lines.joinToString())
        assertEquals("invalid_toollog", call("feedback.toollog", "uuid-alice", """{"name":"alice"}""").lines[0])
        val primitive = call("feedback.toollog", "uuid-alice", """{"name":"alice","action":"fly","details":"true"}""")
        assertTrue(Json.parseToJsonElement(primitive.lines[0]).jsonObject["details"]!!.jsonObject.containsKey("value"))
        val list = Json.parseToJsonElement(call("feedback.toollog.list", "uuid-alice", source = ActionSource.REST).lines[0]).jsonArray
        assertEquals("kronen", list.first().jsonObject["action"]!!.jsonPrimitive.content)
        assertFalse(context.handlers.getValue("feedback.toollog.list").first.bridgeInvocable)
    }

    @Test
    fun `whoami reports the panel user`() {
        val me = context.handlers.getValue("feedback.whoami").second.execute(
            org.helix.api.action.ActionInvocation("feedback.whoami", emptyList(), org.helix.api.action.ActionSource.REST, actor = "Tytoss"),
        )
        assertTrue(me.success)
        assertTrue(me.lines.single().contains("\"name\":\"Tytoss\""))
        val console = context.run("feedback.whoami")
        assertTrue(console.lines.single().contains("\"admin\":true"))
    }

    @Test
    fun `addons acting for a player obey the player limits and events reach the bus`() {
        val body = """{"reporter":{"uuid":"uuid-carl","name":"carl"},"severity":"minor","category":"other","title":"Discord",""" +
            """"description":"aus dem Forum","source":"discord","asPlayer":true}"""
        val first = call("feedback.create", body, source = ActionSource.ADDON)
        assertTrue(first.success, first.lines.joinToString())
        val second = call("feedback.create", body, source = ActionSource.ADDON)
        assertEquals("cooldown", second.lines[0])
        val nr = Json.parseToJsonElement(first.lines[0]).jsonObject["nr"]!!.jsonPrimitive.long
        assertTrue(context.notifications.any { it.first == FeedbackEvent.CATEGORY && it.second.contains("\"nr\":$nr") })
        assertTrue(call("feedback.discord.link", nr.toString(), "777", source = ActionSource.ADDON).success)
        val found = call("feedback.discord.find", "777", source = ActionSource.ADDON)
        assertEquals(nr, Json.parseToJsonElement(found.lines[0]).jsonObject["nr"]!!.jsonPrimitive.long)
        assertFalse(context.handlers.getValue("feedback.discord.link").first.bridgeInvocable)
    }
}
