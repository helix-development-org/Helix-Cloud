package org.helix.addons.guis.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Result lines of one node action invocation. */
private data class ActionOutcome(val success: Boolean, val lines: List<String>)

/**
 * Talks to the node's `POST /api/v1/internal/action` endpoint on behalf of
 * the shared texture database — the bridge action-invocation contract for
 * components holding a per-service token. Not `/api/v1/actions`: that route
 * only ever accepts the admin token or a `helix.admin` session, which a
 * per-service token can never satisfy; the node only lets
 * `/api/v1/internal/action` reach actions explicitly marked
 * `bridgeInvocable` (see `GuisAddon`'s action registrations).
 *
 * @property controlUrl base control API url, for example `http://127.0.0.1:8080`.
 * @property token bearer token for this service, from the `HELIX_CONTROL_TOKEN`
 *  environment variable the wrapper injects.
 */
class GuisNodeClient(private val controlUrl: String, private val token: String) {
    private val logger = LoggerFactory.getLogger(GuisNodeClient::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val endpoint = URI.create(controlUrl.trimEnd('/') + "/api/v1/internal/action")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lists every stored IGui texture definition, as raw per-record JSON.
     *
     * @return the stored definitions' JSON array text, or `null` if the
     *  node is unreachable.
     */
    fun textureListJson(): String? = invoke("guis.texture.list", emptyList())?.takeIf { it.success }?.lines?.firstOrNull()

    /**
     * Reads one stored IGui texture definition.
     *
     * @param id the texture id.
     * @return the definition's raw JSON, or `null` if it does not exist or
     *  the node is unreachable.
     */
    fun textureGetJson(id: String): String? =
        invoke("guis.texture.get", listOf(id))?.takeIf { it.success }?.lines?.firstOrNull()

    /**
     * Stores (or replaces) one IGui texture definition.
     *
     * @param id the texture id.
     * @param recordJson the definition's raw JSON.
     * @return `true` on success.
     */
    fun texturePut(id: String, recordJson: String): Boolean =
        invoke("guis.texture.put", listOf(id, recordJson))?.success == true

    /**
     * Removes one stored IGui texture definition.
     *
     * @param id the texture id.
     * @return `true` if a definition with that id existed and was removed.
     */
    fun textureRemove(id: String): Boolean = invoke("guis.texture.remove", listOf(id))?.success == true

    /**
     * Invokes a node action over HTTP.
     *
     * @return the raw success flag and result lines, or `null` when the
     *  node could not be reached at all.
     */
    private fun invoke(action: String, arguments: List<String>): ActionOutcome? {
        val body = buildJsonObject {
            put("action", action)
            putJsonArray("arguments") { arguments.forEach { add(it) } }
        }
        return runCatching {
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            val parsed = json.parseToJsonElement(response.body()).jsonObject
            val lines = parsed["lines"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            val success = parsed["success"]?.jsonPrimitive?.content == "true"
            ActionOutcome(success, lines)
        }.onFailure { failure ->
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Node action {} failed: {}", action, failure.message)
        }.getOrNull()
    }

    /**
     * Closes the underlying HTTP client, releasing its executor and
     * connection resources — call from the owning plugin's onDisable so a
     * Bukkit `/reload` does not leak the client (and, through it, the old
     * plugin classloader).
     */
    fun close() {
        http.close()
    }
}
