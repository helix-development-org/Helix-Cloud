package org.helix.addons.mineclash.war

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.addons.mineclash.MineClashAddon
import org.helix.addons.mineclash.profile.KronenRewards
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.message.MapMessages
import org.helix.api.player.OnlinePlayer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClanWarCommandTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val clanCalls = CopyOnWriteArrayList<List<String>>()
    private val context = RecordingAddonContext(createTempDirectory("clanwar")).apply {
        online += OnlinePlayer("Alice", "uuid-alice")
        online += OnlinePlayer("Bob", "uuid-bob")
        permissionCheck = { _, _ -> true }
        invocationResult = { invocation -> fakeClanAddon(invocation) }
    }
    private val addon = MineClashAddon().also { it.onEnable(context) }

    @AfterTest
    fun disable() {
        addon.onDisable()
    }

    private fun fakeClanAddon(invocation: ActionInvocation): ActionResult = when (invocation.action) {
        "clan.list" -> ActionResult.ok("""[{"id":"kronritter","name":"Kronritter","tag":"KRON"},{"id":"zulus","name":"Zulus","tag":"ZULU"}]""")
        "clan.detail" -> when (invocation.arguments.first().lowercase()) {
            "kronritter", "kron" -> ActionResult.ok("""{"id":"kronritter","name":"Kronritter","tag":"KRON","members":[{"name":"alice","role":"OWNER"}]}""")
            "zulus", "zulu" -> ActionResult.ok("""{"id":"zulus","name":"Zulus","tag":"ZULU","members":[{"name":"bob","role":"OWNER"}]}""")
            else -> ActionResult.error("no such clan")
        }
        "clan" -> ActionResult.ok("clan answer").also { clanCalls += invocation.arguments }
        else -> ActionResult.ok()
    }

    private fun descriptor(name: String): ActionDescriptor = context.handlers.getValue(name).first

    @Test
    fun `actions are registered with the contract's exposure`() {
        assertTrue(descriptor("clanwar").playerCommand)
        assertEquals("helix.clan", descriptor("clanwar").permission)
        listOf("mineclash.war.poll", "mineclash.war.arrive", "mineclash.war.finish").forEach {
            assertTrue(descriptor(it).bridgeInvocable, it)
            assertEquals(setOf("MineClash-War"), descriptor(it).bridgeTasks, it)
        }
        listOf("mineclash.war.command", "mineclash.clan.command").forEach {
            assertTrue(descriptor(it).bridgeInvocable, it)
            assertEquals(setOf("MineClash-Game", "MineClash-War"), descriptor(it).bridgeTasks, it)
        }
        listOf("mineclash.war.status", "mineclash.war.cancel").forEach {
            assertFalse(descriptor(it).bridgeInvocable, it)
            assertEquals("mineclash.admin", descriptor(it).permission, it)
        }
    }

    @Test
    fun `clanwar replies are rendered for the player and a war reaches the polling server`() {
        val sent = context.run("clanwar", "Alice", "challenge", "ZULU")
        assertTrue(sent.success)
        assertTrue(sent.lines.single().contains("[ZULU]"), sent.lines.single())
        assertTrue(context.run("mineclash.war.command", "Bob", "accept", "KRON").success)

        val status = """{"bootId":"b-1","capacity":2,"pool":{"ready":2,"target":2,"generating":false,"progress":1.0},"mspt":20.0,"wars":[],"rejected":[]}"""
        val poll = context.run("mineclash.war.poll", "MineClash-War-1", *status.split(" ").toTypedArray())
        assertTrue(poll.success, poll.lines.joinToString())
        val desired = json.decodeFromString(WarDesiredState.serializer(), poll.lines.single())
        val war = desired.wars.single()
        assertEquals(listOf("KRON", "ZULU"), war.teams.map { it.tag })

        val refused = json.decodeFromString(WarArrival.serializer(), context.run("mineclash.war.arrive", "MineClash-War-1", "uuid-carl", "Carl").lines.single())
        assertFalse(refused.ok)
        assertTrue(refused.reason!!.isNotBlank() && !refused.reason!!.startsWith("war."), "the refusal is a rendered line")

        val overview = context.run("mineclash.war.status").lines.single()
        assertTrue(overview.contains(war.warId))
        assertTrue(context.run("mineclash.war.cancel", war.warId).success)
        assertFalse(context.run("mineclash.war.poll", "MineClash-War-1", "{broken").success)
    }

    @Test
    fun `finish answers recorded once and duplicate afterwards`() {
        context.run("clanwar", "Alice", "challenge", "ZULU")
        context.run("clanwar", "Bob", "accept", "KRON")
        val status = """{"bootId":"b-1","capacity":2,"pool":{"ready":2,"target":2}}"""
        val warId = json.decodeFromString(WarDesiredState.serializer(), context.run("mineclash.war.poll", "MineClash-War-1", status).lines.single()).wars.single().warId
        context.run("mineclash.war.poll", "MineClash-War-1", """{"bootId":"b-1","capacity":2,"pool":{"ready":1,"target":2},"wars":[{"warId":"$warId","phase":"RUNNING"}]}""")
        val result = """{"warId":"$warId","winner":"B","reason":"ABANDONED","points":{"A":0,"B":40},"endedAt":1,"participants":[{"uuid":"uuid-bob","name":"Bob","team":"B","presentAtEnd":true}]}"""
        assertEquals(listOf("recorded"), context.run("mineclash.war.finish", "MineClash-War-1", result).lines)
        assertEquals(listOf("duplicate"), context.run("mineclash.war.finish", "MineClash-War-1", result).lines)
    }

    @Test
    fun `the clan menu runs clan commands as the player`() {
        val result = context.run("mineclash.clan.command", "Alice", "invite", "Bob")
        assertEquals(listOf("clan answer"), result.lines)
        assertEquals(listOf(listOf("Alice", "invite", "Bob")), clanCalls.toList())
        context.permissionCheck = { _, _ -> false }
        assertFalse(context.run("mineclash.clan.command", "Alice", "leave").success)
    }

    @Test
    fun `effects render messages per recipient, report failed moves and pay wars`() {
        val calls = CopyOnWriteArrayList<ActionInvocation>()
        val invoker = object : ActionInvoker {
            override fun invoke(invocation: ActionInvocation): ActionResult {
                calls += invocation
                return when (invocation.action) {
                    "player.connect" -> ActionResult.error("MineClash-War-1 is not running (STARTING)")
                    else -> ActionResult.ok()
                }
            }

            override fun descriptors(): List<ActionDescriptor> = emptyList()
        }
        val messages = MapMessages(
            mapOf(
                "clanwar.cancelled" to "cancelled vs {tag}: {reason}",
                "clanwar.cancel_reason.CLAN" to "by a clan",
                "clanwar.join.failed" to "move failed",
                "clanwar.result.reward" to "+{kronen}",
            ),
        )
        val runner = WarEffectRunner(invoker, messages, KronenRewards(invoker, { 0 }, { 0 }, { 30 }, { 120 })) { it.run() }
        runner.run(
            listOf(
                WarEffect.Message("Alice", Msg("clanwar.cancelled", mapOf("tag" to "ZULU"), mapOf("reason" to "clanwar.cancel_reason.CLAN"))),
                WarEffect.Connect("Bob", "MineClash-War-1"),
                WarEffect.PayWar("w-1", mapOf("uuid-a" to "A", "uuid-b" to "B"), mapOf("uuid-a" to "Alice", "uuid-b" to "Bob"), "A"),
            ),
        )
        val messagesSent = calls.filter { it.action == "player.message" }.map { it.arguments }
        assertEquals(listOf("Alice", "cancelled vs ZULU: by a clan"), messagesSent[0])
        assertEquals(listOf("Bob", "move failed"), messagesSent[1])
        assertEquals(listOf(listOf("uuid-a", "150", "mineclash", "war:w-1"), listOf("uuid-b", "30", "mineclash", "war:w-1")), calls.filter { it.action == "eco.api.deposit" }.map { it.arguments })
        assertEquals(listOf(listOf("Alice", "+150"), listOf("Bob", "+30")), messagesSent.drop(2))
    }

    @Test
    fun `return target is the first running village service`() {
        val lines = listOf(
            "Lobby-1 [RUNNING] port=25580 players=0/60 executor=DOCKER",
            "MineClash-Game-2 [STARTING] port=25601 players=0/20 executor=DOCKER",
            "MineClash-Game-3 [RUNNING] port=25602 players=1/20 executor=DOCKER",
            "MineClash-War-1 [RUNNING] port=25620 players=0/20 executor=DOCKER",
        )
        assertEquals(listOf("MineClash-Game-3"), ServiceDirectory.parse(lines, "MineClash-Game"))
        val directory = ServiceDirectory(object : ActionInvoker {
            override fun invoke(invocation: ActionInvocation): ActionResult = ActionResult.error("down")
            override fun descriptors(): List<ActionDescriptor> = emptyList()
        }) { "MineClash-Game" }
        directory.refresh()
        assertEquals("MineClash-Game-1", directory.returnTo())
    }
}
