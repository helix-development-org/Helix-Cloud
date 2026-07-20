package org.helix.node.versions

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
