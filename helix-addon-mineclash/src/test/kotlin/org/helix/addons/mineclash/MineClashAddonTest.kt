package org.helix.addons.mineclash

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.addons.mineclash.profile.DocumentProfileRepository
import org.helix.addons.mineclash.profile.MatchRecord
import org.helix.addons.mineclash.profile.PlayerProfile
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MineClashAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("mineclash"))
    private val addon = MineClashAddon().also { it.onEnable(context) }
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `machine api actions are bridge invocable and serve the bundle`() {
        val machineApi = setOf(
            "mineclash.config.bundle", "mineclash.config.version", "mineclash.profile.load", "mineclash.profile.save",
            "mineclash.match.record", "mineclash.queue.join", "mineclash.queue.leave", "mineclash.queue.poll", "mineclash.clan.of",
        )
        val bridge = context.handlers.filterKeys { it in machineApi }
        assertEquals(machineApi, bridge.keys)
        assertTrue(bridge.isNotEmpty())
        bridge.values.forEach { (descriptor, _) -> assertTrue(descriptor.bridgeInvocable, "${descriptor.name} must be bridge invocable") }
        assertFalse(context.handlers.getValue("mineclash.config.set").first.bridgeInvocable)

        val bundle = context.run("mineclash.config.bundle")
        assertTrue(bundle.success)
        val parsed = json.parseToJsonElement(bundle.lines.single()).jsonObject
        assertEquals("5000", parsed.getValue("settings").jsonObject.getValue("war.pointsWin").jsonPrimitive.content)
        assertTrue(context.run("mineclash.config.set", "war.pointsWin", "6000").success)
        assertEquals(context.run("mineclash.config.version").lines.single().toLong(), json.parseToJsonElement(context.run("mineclash.config.bundle").lines.single()).jsonObject.getValue("version").jsonPrimitive.content.toLong())
        assertFalse(context.run("mineclash.config.set", "war.pointsWin", "x").success)
    }

    @Test
    fun `profiles are created with the starter deck and round trip`() {
        val load = context.run("mineclash.profile.load", "11111111-1111-1111-1111-111111111111", "Alice")
        assertTrue(load.success)
        val profile = json.decodeFromString<PlayerProfile>(load.lines.single())
        assertEquals("Alice", profile.name)
        assertEquals(8, profile.deckSlots)
        assertEquals(listOf("zombie", "skelettbogenschuetze", "hoehlenspinne", "snow_golem", "evoker", "phantom", "witch", "blaze"), profile.deck)
        assertEquals(8, profile.cards.size)
        assertEquals(0L, profile.wallet["ROSEN"])

        val changed = profile.copy(wallet = profile.wallet + ("ROSEN" to 30L), wins = 1)
        assertTrue(context.run("mineclash.profile.save", profile.uuid, Json.encodeToString(changed)).success)
        assertFalse(context.run("mineclash.profile.save", "other", Json.encodeToString(changed)).success)
        val reloaded = json.decodeFromString<PlayerProfile>(context.run("mineclash.profile.load", profile.uuid, "Alice").lines.single())
        assertEquals(30L, reloaded.roses)
        assertEquals(1, reloaded.wins)

        assertTrue(context.run("mineclash.match.record", Json.encodeToString(MatchRecord("1V1", 0, listOf(listOf(profile.uuid), listOf("b")), mapOf(profile.uuid to 30)))).success)
        assertTrue(context.run("mineclash.top", "5").lines.single().contains("Alice"))
    }

    @Test
    fun `queue actions form matches for polling services`() {
        assertTrue(context.run("mineclash.queue.poll", "MineClash-Game-1").success)
        assertTrue(context.run("mineclash.queue.join", "a", "Alice", "1V1", "MineClash-Game-1").success)
        assertTrue(context.run("mineclash.queue.join", "b", "Bob", "1V1", "MineClash-Game-1").success)
        assertFalse(context.run("mineclash.queue.join", "b", "Bob", "1V1", "MineClash-Game-1").success)
        val poll = json.parseToJsonElement(context.run("mineclash.queue.poll", "MineClash-Game-1").lines.single()).jsonObject
        assertEquals(1, poll.getValue("matches").let { it as kotlinx.serialization.json.JsonArray }.size)
        assertEquals("queue is empty", context.run("mineclash.queue.status").lines.single())
        assertFalse(context.run("mineclash.queue.leave", "a").success)
    }

    @Test
    fun `clan lookup goes through the clan addon`() {
        context.registerAction(ActionDescriptor("clan.list", "", "")) { ActionResult.ok("""[{"id":"stv","name":"Stevers","tag":"STV"}]""") }
        context.registerAction(ActionDescriptor("clan.detail", "", "")) {
            ActionResult.ok("""{"id":"stv","name":"Stevers","tag":"STV","members":[{"name":"Steve","role":"OWNER"},{"name":"Alex","role":"MEMBER"}]}""")
        }
        val steve = json.parseToJsonElement(context.run("mineclash.clan.of", "steve").lines.single()).jsonObject
        assertEquals("STV", steve.getValue("tag").jsonPrimitive.content)
        assertEquals("OWNER", steve.getValue("role").jsonPrimitive.content)
        assertEquals("null", context.run("mineclash.clan.of", "Nobody").lines.single())
    }

    @Test
    fun `document repository ranks by roses`() {
        val repo = DocumentProfileRepository(InMemoryAddonStorage())
        repo.save(PlayerProfile("a", "A", wallet = mapOf("ROSEN" to 10)))
        repo.save(PlayerProfile("b", "B", wallet = mapOf("ROSEN" to 50)))
        assertEquals(listOf("b", "a"), repo.topByRoses(5).map { it.first })
        assertNull(repo.load("c"))
    }
}
