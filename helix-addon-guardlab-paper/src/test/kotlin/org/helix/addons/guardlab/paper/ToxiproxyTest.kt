package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToxiproxyTest {
    /** In-memory Toxiproxy with one proxy. */
    private class FakeToxiproxy(var proxyExists: Boolean = true) : HttpTransport {
        val toxics = linkedMapOf<String, JsonObject>()
        val calls = mutableListOf<String>()

        override fun send(method: String, url: String, body: String?): Pair<Int, String> {
            val path = url.removePrefix("http://toxi:8474")
            calls += "$method $path"
            return when {
                method == "GET" && path == "/proxies/minecraft" -> if (proxyExists) 200 to "{}" else 404 to "{}"
                method == "POST" && path == "/proxies" -> { proxyExists = true; 201 to "{}" }
                method == "GET" && path == "/proxies/minecraft/toxics" -> 200 to JsonArray(toxics.values.toList()).toString()
                method == "POST" && path == "/proxies/minecraft/toxics" -> {
                    val toxic = Json.parseToJsonElement(body!!).jsonObject
                    toxics[toxic["name"]!!.jsonPrimitive.content] = toxic
                    200 to body
                }
                method == "POST" && path.startsWith("/proxies/minecraft/toxics/") -> {
                    toxics[path.substringAfterLast('/')] = Json.parseToJsonElement(body!!).jsonObject
                    200 to body
                }
                method == "DELETE" && path.startsWith("/proxies/minecraft/toxics/") -> {
                    toxics.remove(path.substringAfterLast('/'))
                    204 to ""
                }
                else -> 404 to "not found"
            }
        }
    }

    private fun latencyOf(fake: FakeToxiproxy, stream: String) =
        fake.toxics.getValue("guardlab_latency_$stream")["attributes"]!!.jsonObject["latency"]!!.jsonPrimitive.int

    @Test
    fun `profiles split the round trip over both directions`() {
        assertTrue(LagProfiles.toxics("none").isEmpty())
        val ping = LagProfiles.toxics("ping250_jitter50")
        assertEquals(setOf("upstream", "downstream"), ping.map { it.stream }.toSet())
        assertTrue(ping.all { it.attributes == mapOf("latency" to 125, "jitter" to 25) })
        assertEquals(LagProfiles.SPIKE_MS / 2, LagProfiles.toxics("spike", spiking = true).first().attributes["latency"])
        assertTrue(LagProfiles.toxics("spike").first().attributes.getValue("latency") < 100)
        SuiteParser.LAG_PROFILES.filter { it != "none" }.forEach { assertTrue(LagProfiles.toxics(it).isNotEmpty(), it) }
        assertEquals(100, LagProfiles.expectedPing("ping100"))
        assertNull(LagProfiles.expectedPing("spike"))
    }

    @Test
    fun `apply creates, updates and removes only guardlab toxics`() {
        val fake = FakeToxiproxy()
        fake.toxics["foreign"] = JsonObject(mapOf("name" to JsonPrimitive("foreign")))
        val client = ToxiproxyClient("http://toxi:8474/", "minecraft", fake)
        assertNull(client.apply("ping100"))
        assertEquals(50, latencyOf(fake, "upstream"))
        assertEquals(50, latencyOf(fake, "downstream"))
        assertNull(client.apply("spike", spiking = true))
        assertEquals(LagProfiles.SPIKE_MS / 2, latencyOf(fake, "downstream"))
        assertTrue("POST /proxies/minecraft/toxics/guardlab_latency_downstream" in fake.calls)
        assertNull(client.apply("none"))
        assertEquals(setOf("foreign"), fake.toxics.keys)
        client.apply("loss2")
        client.clear()
        assertEquals(setOf("foreign"), fake.toxics.keys)
    }

    @Test
    fun `probe and ensureProxy report problems instead of throwing`() {
        val missing = FakeToxiproxy(proxyExists = false)
        val client = ToxiproxyClient("http://toxi:8474", "minecraft", missing)
        assertTrue(client.probe()!!.contains("fehlt"))
        assertNull(client.ensureProxy("0.0.0.0:25570", "helix-proxy-1:25565"))
        assertNull(client.probe())
        val down = ToxiproxyClient("http://toxi:8474", "minecraft") { _, _, _ -> throw IOException("connection refused") }
        assertTrue(down.probe()!!.startsWith("Toxiproxy nicht erreichbar"))
        assertTrue(down.apply("ping100")!!.contains("connection refused"))
        down.clear()
    }
}
