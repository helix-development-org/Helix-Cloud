package org.helix.bridge.paper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal HTTP client for bridge → node communication.
 *
 * @property settings connection settings.
 */
class NodeHttpClient(private val settings: BridgeSettings) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /**
     * Posts a JSON body to a control API path.
     *
     * @param path absolute API path, for example `/api/v1/internal/heartbeat`.
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
        return response.statusCode() in 200..299
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
        return if (response.statusCode() in 200..299) response.body() else null
    }

    /**
     * Gets a JSON document from a control API path.
     *
     * @param path absolute API path.
     * @return the body text, or `null` on non-2xx responses.
     */
    fun getJson(path: String): String? {
        val request = HttpRequest.newBuilder(URI.create(settings.controlUrl + path))
            .header("Authorization", "Bearer ${settings.token}")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() in 200..299) response.body() else null
    }
}
