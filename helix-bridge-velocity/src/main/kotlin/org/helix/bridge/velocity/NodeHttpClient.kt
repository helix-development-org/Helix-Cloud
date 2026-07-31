package org.helix.bridge.velocity

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal HTTP client for bridge → node communication.
 *
 * Non-2xx responses still yield `null`/`false`, but are additionally logged
 * through [warn] — throttled to one line per path and status change, so a
 * persistent failure (say, a misconfigured token answering 401 on every
 * poll) shows up once instead of flooding the log every second.
 *
 * @property settings connection settings.
 * @property warn sink for throttled non-2xx warnings; defaults to JUL so the
 *  message is never silently dropped when no plugin logger is wired in.
 */
class NodeHttpClient(
    private val settings: BridgeSettings,
    private val warn: (String) -> Unit = { message ->
        java.util.logging.Logger.getLogger("HelixBridge").warning(message)
    },
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val lastStatusByPath = ConcurrentHashMap<String, Int>()

    /**
     * Posts a JSON body to a control API path.
     *
     * @param path absolute API path.
     * @param json request body.
     * @return `true` on a 2xx response.
     */
    fun postJson(path: String, json: String): Boolean {
        val request = HttpRequest.newBuilder(URI.create(settings.controlUrl + path))
            .header("Authorization", "Bearer ${settings.token}")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return trackStatus(path, response.statusCode())
    }

    /**
     * Posts a JSON body and returns the response body.
     *
     * @param path absolute API path.
     * @param json request body.
     * @return the body text, or `null` on non-2xx responses.
     */
    fun postJsonForBody(path: String, json: String): String? {
        val request = HttpRequest.newBuilder(URI.create(settings.controlUrl + path))
            .header("Authorization", "Bearer ${settings.token}")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return if (trackStatus(path, response.statusCode())) response.body() else null
    }

    /**
     * Gets a JSON document from a control API path.
     *
     * @param path absolute API path including query parameters.
     * @return the body text, or `null` on non-2xx responses.
     */
    fun getJson(path: String): String? {
        val request = HttpRequest.newBuilder(URI.create(settings.controlUrl + path))
            .header("Authorization", "Bearer ${settings.token}")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return if (trackStatus(path, response.statusCode())) response.body() else null
    }

    /**
     * Gets a JSON document with a long timeout, for long-polling the node.
     *
     * @param path absolute API path including query parameters.
     * @return the body text, or `null` on non-2xx responses.
     */
    fun getJsonLong(path: String): String? {
        val request = HttpRequest.newBuilder(URI.create(settings.controlUrl + path))
            .header("Authorization", "Bearer ${settings.token}")
            .timeout(Duration.ofSeconds(40))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return if (trackStatus(path, response.statusCode())) response.body() else null
    }

    /**
     * Records the response status for a path and warns on non-2xx — but only
     * when the status DIFFERS from the previous response on that path, so a
     * steady failure logs once and logs again after recovering and failing anew.
     *
     * @param path the requested path (query string is ignored for the key).
     * @param statusCode the HTTP status the node answered with.
     * @return `true` when the status is 2xx.
     */
    private fun trackStatus(path: String, statusCode: Int): Boolean {
        val key = path.substringBefore('?')
        if (statusCode in 200..299) {
            lastStatusByPath.remove(key)
            return true
        }
        if (lastStatusByPath.put(key, statusCode) != statusCode) {
            warn("Node answered HTTP $statusCode on $key — further identical failures are not logged")
        }
        return false
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
