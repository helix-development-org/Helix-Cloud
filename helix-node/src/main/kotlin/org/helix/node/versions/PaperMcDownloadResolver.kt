package org.helix.node.versions

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.api.environment.Environment
import org.slf4j.LoggerFactory

/**
 * Resolves stable server jar downloads from the PaperMC Fill v3 API.
 *
 * One resolver serves every environment: the environment maps to the Fill
 * project (`paper`, `velocity`), the newest `STABLE` build wins.
 *
 * @property fetcher HTTP client used for API calls.
 * @property baseUri base URI of the Fill v3 API.
 * @property userAgent user agent sent to PaperMC.
 */
class PaperMcDownloadResolver(
    private val fetcher: HttpFetcher = JavaHttpFetcher(),
    private val baseUri: URI = URI.create("https://fill.papermc.io/v3"),
    private val userAgent: String = "helix-cloud",
) {
    private val logger = LoggerFactory.getLogger(PaperMcDownloadResolver::class.java)

    /**
     * Resolves the download URI of the latest stable build.
     *
     * @param environment platform to download.
     * @param version platform version, for example `1.21.11`.
     * @return URI of the server jar.
     * @throws IllegalArgumentException if the API request fails or no stable
     *   build exists for the version.
     */
    fun resolve(environment: Environment, version: String): URI {
        val project = when (environment) {
            Environment.PAPER -> "paper"
            Environment.VELOCITY -> "velocity"
        }
        val uri = baseUri.resolve("/v3/projects/$project/versions/$version/builds")
        logger.info("Resolving {} {} via {}", project, version, uri)
        val response = fetcher.get(uri, mapOf("User-Agent" to userAgent))
        require(response.statusCode in 200..299) {
            "PaperMC builds request for $project $version failed with HTTP ${response.statusCode}"
        }
        val build = Json.parseToJsonElement(response.bodyText()).jsonArray
            .map { it.jsonObject }
            .filter { it["channel"]?.jsonPrimitive?.contentOrNull == "STABLE" }
            .maxByOrNull { it["id"]?.jsonPrimitive?.intOrNull ?: -1 }
            ?: throw IllegalArgumentException("no stable $project build for version $version")
        val url = build["downloads"]?.jsonObject
            ?.get("server:default")?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("stable $project build has no server:default download url")
        return URI.create(url)
    }
}
