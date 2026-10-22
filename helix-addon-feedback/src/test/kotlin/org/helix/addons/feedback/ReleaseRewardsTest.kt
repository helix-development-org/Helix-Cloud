package org.helix.addons.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.release.ReleaseReset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseRewardsTest {
    private val table = listOf(
        Json.parseToJsonElement("""{"minPoints":1,"kronen":500,"note":"Danke"}""").jsonObject,
        Json.parseToJsonElement("""{"minPoints":5,"kronen":2000,"keys":["standard"],"items":["model:hut_krone","cosmetic:cosmetic.wings.gold"]}""").jsonObject,
    )

    @Test
    fun `the highest reached tier wins`() {
        assertNull(ReleaseRewards.tierFor(0, table))
        assertEquals(500L, ReleaseRewards.tierFor(4, table)!!["kronen"]!!.jsonPrimitive.long)
        assertEquals(2000L, ReleaseRewards.tierFor(9, table)!!["kronen"]!!.jsonPrimitive.long)
    }

    @Test
    fun `tiers split into a network and a citybuild part`() {
        val parts = ReleaseRewards.parts(table[1], 9)
        assertEquals(listOf(ReleaseRewards.PART_NETWORK, ReleaseRewards.PART_CITYBUILD), parts.map { ReleaseRewards.partOf(it) })
        val network = parts[0]
        assertEquals(listOf("cosmetic.wings.gold"), network["permissions"]!!.jsonArray.map { it.jsonPrimitive.content })
        val city = parts[1]
        assertEquals(listOf("model:hut_krone"), city["items"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("standard"), city["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(parts.all(ReleaseRewards::isRelease))
        assertEquals(listOf(ReleaseRewards.PART_NETWORK), ReleaseRewards.parts(table[0], 2).map { ReleaseRewards.partOf(it) })
    }

    private val context = RecordingAddonContext(createTempDirectory("feedback-release"))
    private var ecoFails = false

    init {
        context.invocationResult = { inv ->
            when {
                inv.action == "eco.api.deposit" && ecoFails -> ActionResult.error("economy down")
                else -> ActionResult.ok("1500")
            }
        }
        context.permissionCheck = { _, perm -> perm == "feedback.report" }
        FeedbackAddon(clock = TestClock()).onEnable(context)
        listOf("alice", "bob").forEach { call("feedback.agreement.accept", "uuid-$it", source = ActionSource.BRIDGE) }
    }

    private fun call(action: String, vararg args: String, source: ActionSource = ActionSource.REST): ActionResult =
        context.handlers.getValue(action).second.execute(ActionInvocation(action, args.toList(), source))

    private fun confirmedReportOf(name: String) {
        val body = """{"reporter":{"uuid":"uuid-$name","name":"$name"},"severity":"crash","category":"lobby","title":"Absturz",""" +
            """"description":"weg","context":{"service":"Lobby-1"}}"""
        val nr = Json.parseToJsonElement(call("feedback.create", body, source = ActionSource.BRIDGE).lines[0]).jsonObject["nr"]!!.jsonPrimitive.long
        assertTrue(call("feedback.status", """{"nr":$nr,"status":"confirmed"}""").success)
    }

    private fun pending(uuid: String): List<JsonObject> =
        Json.parseToJsonElement(call("feedback.rewards.pending", uuid).lines[0]).jsonArray.map { it.jsonObject }

    @Test
    fun `release reset plans, grants once, badges point holders and closes the test phase`() {
        confirmedReportOf("alice")
        assertTrue(call("feedback.rewards.table", Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(table))).success)

        val plan = ReleaseReset.parse(call("feedback.release.reset", "plan").lines[0])!!
        assertEquals(ReleaseReset.PLAN, plan.mode)
        assertTrue(pending("uuid-alice").isEmpty(), "a plan changes nothing")

        val run = ReleaseReset.parse(call("feedback.release.reset", "run").lines[0])!!
        assertEquals(ReleaseReset.RUN, run.mode)
        assertEquals(2, pending("uuid-alice").size, "6 points reach the second tier: network + citybuild part")
        assertTrue(context.invocations.any { it.action == "perm.user.grant" && it.arguments == listOf("alice", "badge.betatester") })
        assertTrue(context.bridgeValues["feedback.settings"]!!.contains("\"testphase\":false"))

        call("feedback.release.reset", "run")
        assertEquals(2, pending("uuid-alice").size, "a second run grants nothing new")
    }

    @Test
    fun `redeeming pays crowns once and reopens the reward when the payout fails`() {
        confirmedReportOf("bob")
        call("feedback.rewards.table", """[{"minPoints":1,"kronen":500}]""")
        call("feedback.release.reset", "run")
        val id = pending("uuid-bob").single()["id"]!!.jsonPrimitive.content

        assertEquals("forbidden", call("feedback.rewards.redeem", "uuid-bob", id, "bob", source = ActionSource.BRIDGE).lines[0], "bob is not online")
        context.online += org.helix.api.player.OnlinePlayer("bob", "uuid-bob", "Proxy-1", 0L)

        ecoFails = true
        assertEquals("payout_failed", call("feedback.rewards.redeem", "uuid-bob", id, "bob", source = ActionSource.BRIDGE).lines[0])
        assertEquals(1, pending("uuid-bob").size)

        ecoFails = false
        assertTrue(call("feedback.rewards.redeem", "uuid-bob", id, "bob", source = ActionSource.BRIDGE).success)
        assertTrue(context.invocations.any { it.action == "eco.api.deposit" && it.arguments == listOf("uuid-bob", "500", "release-reward", id) })
        assertTrue(pending("uuid-bob").isEmpty())
        assertEquals("claimed", call("feedback.rewards.redeem", "uuid-bob", id, "bob", source = ActionSource.BRIDGE).lines[0])
        assertFalse(context.handlers.getValue("feedback.release.reset").first.bridgeInvocable)
        assertFalse(context.handlers.getValue("feedback.rewards.claim").first.bridgeInvocable)
    }
}
