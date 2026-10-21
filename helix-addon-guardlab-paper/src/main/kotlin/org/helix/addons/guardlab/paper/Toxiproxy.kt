package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One Toxiproxy toxic.
 *
 * @property name unique name (GuardLab prefixes its toxics with `guardlab_`).
 * @property type toxic type (`latency`, …).
 * @property stream `upstream` (client → server) or `downstream` (server → client).
 * @property attributes type attributes.
 */
data class Toxic(val name: String, val type: String, val stream: String, val attributes: Map<String, Int>)

/**
 * The lag profiles of the suite file, as Toxiproxy toxics on the client connection.
 *
 * Latency is split evenly over both directions so the round trip matches the profile name. Toxiproxy works
 * on TCP, which cannot lose packets; `loss2` therefore emulates the effect of ~2 % loss on a TCP stream –
 * irregular retransmission stalls – with a small base latency and a large jitter.
 * `spike` is a base latency the runner periodically raises to [SPIKE_MS] for [SPIKE_TICKS] ticks.
 */
object LagProfiles {
    /** Round trip during a spike. */
    const val SPIKE_MS = 1200

    /** Spike length in ticks. */
    const val SPIKE_TICKS = 20

    /** Ticks between spike starts. */
    const val SPIKE_EVERY_TICKS = 100

    /**
     * Toxics of a profile.
     *
     * @param profile profile id.
     * @param spiking whether a `spike` profile is currently in its spike window.
     * @return the toxics (empty for `none`).
     */
    fun toxics(profile: String, spiking: Boolean = false): List<Toxic> = when (profile) {
        "ping100" -> latency(100, 0)
        "ping250_jitter50" -> latency(250, 50)
        "loss2" -> latency(40, 60)
        "spike" -> if (spiking) latency(SPIKE_MS, 0) else latency(40, 0)
        else -> emptyList()
    }

    /**
     * Round trip the profile should add, for the "lag not effective" warning.
     *
     * @param profile profile id.
     * @return milliseconds, or null when the profile has no stable latency.
     */
    fun expectedPing(profile: String): Int? = when (profile) {
        "ping100" -> 100
        "ping250_jitter50" -> 250
        else -> null
    }

    private fun latency(roundTrip: Int, jitter: Int): List<Toxic> = listOf("upstream", "downstream").map { stream ->
        Toxic("guardlab_latency_$stream", "latency", stream, mapOf("latency" to roundTrip / 2, "jitter" to jitter / 2))
    }
}

/** Minimal HTTP access (a fake in tests). */
fun interface HttpTransport {
    /**
     * Sends a request.
     *
     * @param method HTTP method.
     * @param url absolute URL.
     * @param body JSON body or null.
     * @return status code and body.
     */
    fun send(method: String, url: String, body: String?): Pair<Int, String>
}

/** [HttpTransport] over the JDK client. */
class JdkHttpTransport : HttpTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    /** Sends a request with a short timeout. */
    override fun send(method: String, url: String, body: String?): Pair<Int, String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }
}

/**
 * Applies lag profiles through the Toxiproxy API. Only toxics named `guardlab_*` are touched.
 * Calls block (HTTP) – run them off the main thread.
 *
 * @property baseUrl API base, e.g. `http://kronland-local-toxiproxy:8474`.
 * @property proxy proxy name the client connects through.
 * @property transport HTTP access.
 */
class ToxiproxyClient(
    private val baseUrl: String,
    private val proxy: String,
    private val transport: HttpTransport = JdkHttpTransport(),
) {
    private val root = baseUrl.trimEnd('/')

    /**
     * Checks the API and the proxy.
     *
     * @return null when usable, otherwise the problem.
     */
    fun probe(): String? = runCatching {
        val (status, _) = transport.send("GET", "$root/proxies/$proxy", null)
        when (status) {
            200 -> null
            404 -> "Proxy '$proxy' fehlt in Toxiproxy"
            else -> "Toxiproxy antwortet mit HTTP $status"
        }
    }.getOrElse { "Toxiproxy nicht erreichbar ($root): ${it.message}" }

    /**
     * Creates the proxy when it is missing.
     *
     * @param listen listen address inside the Toxiproxy container.
     * @param upstream upstream address.
     * @return null on success, otherwise the problem.
     */
    fun ensureProxy(listen: String, upstream: String): String? = runCatching {
        val (status, _) = transport.send("GET", "$root/proxies/$proxy", null)
        if (status == 200) return@runCatching null
        val body = buildJsonObject {
            put("name", proxy)
            put("listen", listen)
            put("upstream", upstream)
            put("enabled", true)
        }
        val (created, text) = transport.send("POST", "$root/proxies", body.toString())
        if (created in 200..299) null else "Proxy anlegen fehlgeschlagen: HTTP $created $text"
    }.getOrElse { "Toxiproxy nicht erreichbar ($root): ${it.message}" }

    /**
     * Replaces the GuardLab toxics with those of [profile].
     *
     * @param profile profile id.
     * @param spiking spike window of the `spike` profile.
     * @return null on success, otherwise the problem.
     */
    fun apply(profile: String, spiking: Boolean = false): String? = runCatching {
        val wanted = LagProfiles.toxics(profile, spiking)
        val existing = guardlabToxics()
        (existing - wanted.map { it.name }.toSet()).forEach { delete(it) }
        wanted.forEach { toxic ->
            val body = toxicJson(toxic).toString()
            val (status, text) = if (toxic.name in existing) {
                transport.send("POST", "$root/proxies/$proxy/toxics/${toxic.name}", body)
            } else {
                transport.send("POST", "$root/proxies/$proxy/toxics", body)
            }
            if (status !in 200..299) return@runCatching "Toxic ${toxic.name}: HTTP $status $text"
        }
        null
    }.getOrElse { "Toxiproxy nicht erreichbar ($root): ${it.message}" }

    /** Removes every GuardLab toxic; errors are ignored. */
    fun clear() {
        runCatching { guardlabToxics().forEach { delete(it) } }
    }

    private fun guardlabToxics(): Set<String> {
        val (status, text) = transport.send("GET", "$root/proxies/$proxy/toxics", null)
        if (status != 200) return emptySet()
        val list = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptySet()
        return list.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
            .filter { it.startsWith("guardlab_") }
            .toSet()
    }

    private fun delete(name: String) {
        transport.send("DELETE", "$root/proxies/$proxy/toxics/$name", null)
    }

    private fun toxicJson(toxic: Toxic): JsonObject = buildJsonObject {
        put("name", toxic.name)
        put("type", toxic.type)
        put("stream", toxic.stream)
        put("toxicity", 1.0)
        putJsonObject("attributes") { toxic.attributes.forEach { (key, value) -> put(key, value) } }
    }
}
