package org.helix.addons.npc.paper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal HTTP client for the node control API, configured from the same
 * `HELIX_CONTROL_URL`/`HELIX_CONTROL_TOKEN` environment the bridge uses.
 *
 * @property controlUrl base URL of the node control API.
 * @property token per-service bearer token (`ServiceTokenRegistry`), scoped
 *  to this service's bridge routes — not an admin token.
 */
class NodeClient(
    val controlUrl: String,
    private val token: String,
) {
    private val logger = LoggerFactory.getLogger(NodeClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /**
     * Posts a JSON body to a path.
     *
     * @param path path below the control URL, starting with `/`.
     * @param body the JSON payload.
     * @return the response body, or `null` on non-2xx or transport failure.
     */
    fun postJson(path: String, body: String): String? = exchange(path) {
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    /**
     * Invokes a node action and returns its first result line.
     *
     * Goes through `POST /api/v1/internal/action`, not `/api/v1/actions` —
     * that route only ever accepts the admin token or a `helix.admin`
     * session, which this client's per-service token can never satisfy. The
     * node only lets `/internal/action` reach actions explicitly marked
     * `bridgeInvocable` (see `NpcAddon`'s action registrations).
     *
     * @param name action name, for example `npc.list`.
     * @param args action arguments.
     * @return the first line of a successful result, or `null`.
     */
    fun action(name: String, vararg args: String): String? {
        val invocation = ActionCall(name, args.toList())
        val body = postJson("/api/v1/internal/action", json.encodeToString(invocation)) ?: return null
        val result = runCatching { json.decodeFromString<ActionReply>(body) }.getOrNull() ?: return null
        return if (result.success) result.lines.firstOrNull() else null
    }

    /**
     * Sends one request, logging failures instead of swallowing them: a
     * non-2xx status logs once per status change (so a steady 401 does not
     * spam), transport failures log their message, and an interrupt restores
     * the thread's interrupt flag before returning.
     *
     * @param path request path, for the log line.
     * @param build builds the request to send.
     * @return the body on 2xx, `null` otherwise.
     */
    private fun exchange(path: String, build: () -> HttpRequest): String? {
        try {
            val response = client.send(build(), HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            if (status in 200..299) {
                lastLoggedStatus = 0
                return response.body()
            }
            if (lastLoggedStatus != status) {
                lastLoggedStatus = status
                logger.warn("Node request {} failed: HTTP {}", path, status)
            }
            return null
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (failure: Exception) {
            if (lastLoggedStatus != -1) {
                lastLoggedStatus = -1
                logger.warn("Node request {} failed: {}", path, failure.message)
            }
            return null
        }
    }

    /** Last logged failure signal (status code, -1 transport, 0 healthy), throttling repeat logs. */
    @Volatile
    private var lastLoggedStatus = 0

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create(controlUrl.trimEnd('/') + path))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer $token")

    /**
     * Action invocation payload of `POST /api/v1/internal/action`.
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

    /**
     * Closes the underlying HTTP client, releasing its executor and
     * connection resources — call from the owning plugin's onDisable so a
     * Bukkit `/reload` does not leak the client (and, through it, the old
     * plugin classloader).
     */
    fun close() {
        client.close()
    }
}
