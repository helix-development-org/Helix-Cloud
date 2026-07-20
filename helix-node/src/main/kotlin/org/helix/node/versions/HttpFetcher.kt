package org.helix.node.versions

import java.net.URI

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
