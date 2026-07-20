package org.helix.node.versions

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
