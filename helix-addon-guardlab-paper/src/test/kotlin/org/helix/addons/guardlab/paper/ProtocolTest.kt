package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolTest {
    private fun decode(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun `server messages carry the contract fields`() {
        val hello = decode(Protocol.hello("tok_0123456789abcdef", "all", 88))
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals("tok_0123456789abcdef", hello["token"]!!.jsonPrimitive.content)
        assertEquals(88, hello["scenarios"]!!.jsonPrimitive.int)

        val script = JsonArray(listOf(JsonObject(mapOf("ticks" to JsonPrimitive(20), "keys" to JsonArray(listOf(JsonPrimitive("forward")))))))
        val cheat = JsonObject(mapOf("mode" to JsonPrimitive("speed"), "params" to JsonObject(mapOf("factor" to JsonPrimitive(1.8)))))
        val scenario = decode(Protocol.scenario("cheat_speed", 3, 60, script, cheat, 120))
        assertEquals(listOf("type", "id", "index", "setupTicks", "script", "cheat", "durationTicks"), scenario.keys.toList())
        assertEquals("forward", scenario["script"]!!.jsonArray[0].jsonObject["keys"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("speed", scenario["cheat"]!!.jsonObject["mode"]!!.jsonPrimitive.content)

        assertEquals("abort", decode(Protocol.abort("x"))["type"]!!.jsonPrimitive.content)
        val done = decode(Protocol.done("plugins/GuardLab/reports/x/report.md", 10, 2))
        assertEquals(2, done["failed"]!!.jsonPrimitive.int)
        assertEquals("quit", decode(Protocol.quit())["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `client messages parse and garbage is ignored`() {
        fun parse(text: String) = Protocol.parseClient(text.toByteArray())
        assertEquals(ClientMessage.Ready("1.2.0", "labymod"), parse("""{"type":"ready","version":"1.2.0","brand":"labymod"}"""))
        assertEquals(ClientMessage.Started("a"), parse("""{"type":"started","id":"a"}"""))
        assertEquals(ClientMessage.Finished("a", 140), parse("""{"type":"finished","id":"a","ticks":140,"extra":true}"""))
        assertEquals(ClientMessage.Finished("a", 0), parse("""{"type":"finished","id":"a"}"""))
        assertEquals(ClientMessage.Error("", "unsupported cheat mode nuker"), parse("""{"type":"error","reason":"unsupported cheat mode nuker"}"""))
        assertNull(parse("""{"type":"hello"}"""))
        assertNull(parse("""{"id":"a"}"""))
        assertNull(parse("not json"))
        assertNull(parse("[1,2]"))
    }
}
