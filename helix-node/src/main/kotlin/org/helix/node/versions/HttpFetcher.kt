package org.helix.node.versions

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Response of a single HTTP fetch.
 *
 * @property statusCode HTTP status code.
 * @property body raw response body.
 */
data class HttpFetchResponse(
    val statusCode: Int,
    val body: ByteArray,
) {
    /**
     * Decodes the body as UTF-8 text.
     *
     * @return the body text.
     */
    fun bodyText(): String = body.decodeToString()

    /** Structural equality including the body bytes. */
    override fun equals(other: Any?): Boolean =
        other is HttpFetchResponse && other.statusCode == statusCode && other.body.contentEquals(body)

    /** Hash consistent with [equals]. */
    override fun hashCode(): Int = 31 * statusCode + body.contentHashCode()
}

/**
 * Minimal HTTP abstraction so download logic stays testable offline.
 */
fun interface HttpFetcher {
    /**
     * Performs a GET request.
     *
     * @param uri target of the request.
     * @param headers additional request headers.
     * @return status code and body.
     */
    fun get(uri: URI, headers: Map<String, String>): HttpFetchResponse
}

/**
 * [HttpFetcher] backed by the JDK [HttpClient], following redirects.
 */
class JavaHttpFetcher : HttpFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Performs a GET request with the JDK http client.
     *
     * @param uri target of the request.
     * @param headers additional request headers.
     * @return status code and body.
     */
    override fun get(uri: URI, headers: Map<String, String>): HttpFetchResponse {
        val request = HttpRequest.newBuilder(uri).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
        return HttpFetchResponse(response.statusCode(), response.body())
    }
}
