package org.helix.addons.npc

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.testing.RecordingAddonContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpcAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("npc"))
    private val addon = NpcAddon().also { it.onEnable(context) }
    private val json = Json { ignoreUnknownKeys = true }

    private fun save(def: NpcDef) = context.run("npc.save", json.encodeToString(def))

    private fun list(task: String? = null): List<NpcDef> =
        json.decodeFromString(
            (task?.let { context.run("npc.list", it) } ?: context.run("npc.list")).lines.single(),
        )

    private fun sample(id: String, task: String = "lobby") = NpcDef(
        id = id,
        task = task,
        world = "world",
        x = 1.0,
        y = 64.0,
        z = 2.0,
        yaw = 90f,
        skin = "Notch",
        hologramLines = listOf("<gold>Hi", "<gray>click me"),
        lookMode = "nearest",
        interactAction = "server Lobby",
    )

    @Test
    fun `save then get round-trips the definition and lowercases the id`() {
        assertTrue(save(sample("Greeter")).success)

        val result = context.run("npc.get", "greeter")
        assertTrue(result.success)
        val stored = json.decodeFromString<NpcDef>(result.lines.single())
        assertEquals(sample("greeter"), stored)
    }

    @Test
    fun `save upserts in place rather than duplicating`() {
        save(sample("g"))
        save(sample("g").copy(skin = "jeb_"))

        assertEquals(1, list().size)
        assertEquals("jeb_", json.decodeFromString<NpcDef>(context.run("npc.get", "g").lines.single()).skin)
    }

    @Test
    fun `list filters by task and always includes wildcard NPCs`() {
        save(sample("a", task = "lobby"))
        save(sample("b", task = "survival"))
        save(sample("c", task = "*"))

        assertEquals(listOf("a", "c"), list("lobby").map { it.id })
        assertEquals(listOf("b", "c"), list("survival").map { it.id })
        assertEquals(listOf("a", "b", "c"), list().map { it.id })
    }

    @Test
    fun `delete reports whether an NPC existed`() {
        save(sample("gone"))

        val first = json.decodeFromString<NpcAck>(context.run("npc.delete", "gone").lines.single())
        assertTrue(first.ok)
        assertTrue(first.removed)

        val second = json.decodeFromString<NpcAck>(context.run("npc.delete", "gone").lines.single())
        assertTrue(second.ok)
        assertFalse(second.removed)
        assertTrue(list().isEmpty())
    }

    @Test
    fun `get and delete of unknown ids behave predictably`() {
        assertFalse(context.run("npc.get", "nope").success)
        assertFalse(json.decodeFromString<NpcAck>(context.run("npc.delete", "nope").lines.single()).removed)
    }

    @Test
    fun `invalid payloads are rejected`() {
        assertFalse(context.run("npc.save", "not json").success)
        assertFalse(context.run("npc.save").success)
        assertFalse(save(sample("bad id")).success)
        assertFalse(save(sample("x").copy(world = " ")).success)
        assertFalse(save(sample("x").copy(lookMode = "sideways")).success)
        assertFalse(save(sample("x").copy(hologramLines = List(11) { "l" })).success)
        assertTrue(list().isEmpty())
    }

    @Test
    fun `defaults are applied for optional fields`() {
        val minimal = """{"id":"m","world":"world","x":0.0,"y":0.0,"z":0.0}"""
        assertTrue(context.run("npc.save", minimal).success)

        val stored = json.decodeFromString<NpcDef>(context.run("npc.get", "m").lines.single())
        assertEquals("*", stored.task)
        assertEquals("self", stored.skin)
        assertEquals("none", stored.lookMode)
        assertNull(stored.interactAction)
        assertTrue(stored.hologramLines.isEmpty())
    }

    @Test
    fun `store persists across instances`() {
        val storage = org.helix.api.storage.InMemoryAddonStorage()
        NpcStore(storage).upsert(sample("keep"))

        assertEquals(listOf("keep"), NpcStore(storage).list().map { it.id })
    }
}
