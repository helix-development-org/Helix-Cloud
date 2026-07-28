package org.helix.addons.subtitles.paper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Reads the node's published bridge values, scoped to this service's task
 * (so a subtitle disabled for this task never leaks in), the same
 * `GET /internal/bridge-values` contract the main Paper bridge itself
 * polls for tablist/chat/scoreboard content.
 *
 * @property controlUrl base control API url, for example `http://127.0.0.1:8080`.
 * @property token bearer token for this service.
 * @property serviceId this service's id, to scope the values to its task's
 *  active addons.
 */
class BridgeValuesClient(
    private val controlUrl: String,
    private val token: String,
    private val serviceId: String,
) {
    private val logger = LoggerFactory.getLogger(BridgeValuesClient::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches every currently published bridge value.
     *
     * @return key to value, or `null` if the node is unreachable.
     */
    fun fetch(): Map<String, String>? = runCatching {
        val request = HttpRequest.newBuilder(
            URI.create("${controlUrl.trimEnd('/')}/api/v1/internal/bridge-values?serviceId=$serviceId"),
        )
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        json.parseToJsonElement(response.body()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.onFailure { logger.warn("Could not fetch bridge values: {}", it.message) }.getOrNull()
}
