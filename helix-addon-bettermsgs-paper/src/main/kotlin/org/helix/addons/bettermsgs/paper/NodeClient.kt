package org.helix.addons.bettermsgs.paper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal HTTP client for the node control API, configured from the same
 * `HELIX_CONTROL_URL`/`HELIX_CONTROL_TOKEN` environment the bridge uses.
 *
 * @property controlUrl base URL of the node control API.
 * @property token admin bearer token.
 */
class NodeClient(
    val controlUrl: String,
    private val token: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /**
     * Fetches a path as string body.
     *
     * @param path path below the control URL, starting with `/`.
     * @return the body, or `null` on non-2xx or transport failure.
     */
    fun getJson(path: String): String? = runCatching {
        val response = client.send(
            request(path).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        response.body().takeIf { response.statusCode() in 200..299 }
    }.getOrNull()

    /**
     * Posts a JSON body to a path.
     *
     * @param path path below the control URL, starting with `/`.
     * @param body the JSON payload.
     * @return the response body, or `null` on non-2xx or transport failure.
     */
    fun postJson(path: String, body: String): String? = runCatching {
        val response = client.send(
            request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        response.body().takeIf { response.statusCode() in 200..299 }
    }.getOrNull()

    /**
     * Invokes a node action and returns its first result line.
     *
     * @param name action name, for example `bettermsgs.history`.
     * @param args action arguments.
     * @return the first line of a successful result, or `null`.
     */
    fun action(name: String, vararg args: String): String? {
        val invocation = ActionCall(name, args.toList())
        val body = postJson("/api/v1/actions", json.encodeToString(invocation)) ?: return null
        val result = runCatching { json.decodeFromString<ActionReply>(body) }.getOrNull() ?: return null
        return if (result.success) result.lines.firstOrNull() else null
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create(controlUrl + path))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer $token")

    /**
     * Action invocation payload of `POST /api/v1/actions`.
     *
     * @property action action name.
     * @property arguments action arguments.
     */
    @Serializable
    private data class ActionCall(val action: String, val arguments: List<String>)

    /**
     * Action result payload.
     *
     * @property success whether the action succeeded.
     * @property lines result lines.
     */
    @Serializable
    private data class ActionReply(val success: Boolean = false, val lines: List<String> = emptyList())

    companion object {
        /**
         * Builds a client from the wrapper environment.
         *
         * @return the client, or `null` when the Helix environment is absent.
         */
        fun fromEnvironment(): NodeClient? {
            val url = System.getenv("HELIX_CONTROL_URL") ?: return null
            val token = System.getenv("HELIX_CONTROL_TOKEN") ?: return null
            return NodeClient(url, token)
        }
    }
}
