package org.helix.node.versions

import org.helix.api.environment.Environment
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Provides server jars for services, downloading and caching them under
 * `Helix/cache/`.
 *
 * @property cacheDirectory jar cache, one file per environment + version.
 * @property catalog configured versions with optional URL overrides.
 * @property resolver PaperMC API resolver used when no override is set.
 * @property fetcher HTTP client used for the actual jar download.
 */
class ServerJarProvider(
    private val cacheDirectory: Path,
    private val catalog: VersionCatalog,
    private val resolver: PaperMcDownloadResolver = PaperMcDownloadResolver(),
    private val fetcher: HttpFetcher = JavaHttpFetcher(),
) {
    private val logger = LoggerFactory.getLogger(ServerJarProvider::class.java)

    /**
     * Returns the cached server jar, downloading it on first use.
     *
     * A jar resolved through the PaperMC API is verified against the
     * sha256 checksum the API reports before it is cached or used, so a
     * corrupted or tampered download fails the service start instead of
     * silently running. A `versions.toml` URL override is trusted as
     * configured (there is no checksum to compare against) but must be
     * `https://`.
     *
     * @param environment platform to provide.
     * @param version platform version to provide.
     * @return path of the cached jar.
     * @throws IllegalArgumentException if the download fails, the override
     *   URL is not `https://`, or the downloaded bytes don't match the
     *   expected sha256.
     */
    fun ensureJar(environment: Environment, version: String): Path {
        val target = cacheDirectory.resolve("${environment.name.lowercase()}-$version.jar")
        if (Files.exists(target)) {
            return target
        }
        Files.createDirectories(cacheDirectory)
        val override = catalog.find(environment, version)?.url
        val (uri, expectedSha256) = if (override != null) {
            require(override.startsWith("https://", ignoreCase = true)) {
                "versions.toml override for $environment $version must be an https:// url: $override"
            }
            URI.create(override) to null
        } else {
            val resolved = resolver.resolve(environment, version)
            resolved.uri to resolved.sha256
        }
        logger.info("Downloading {} {} from {}", environment, version, uri)
        val response = fetcher.get(uri, mapOf("User-Agent" to "helix-cloud"))
        require(response.statusCode in 200..299) {
            "download of $environment $version failed with HTTP ${response.statusCode}"
        }
        if (expectedSha256 != null) {
            val actualSha256 = sha256Hex(response.body)
            require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                "download of $environment $version failed sha256 verification: " +
                    "expected $expectedSha256, got $actualSha256"
            }
        }
        val temp = Files.createTempFile(cacheDirectory, "download", ".part")
        Files.write(temp, response.body)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        logger.info("Cached {} {} at {} ({} bytes)", environment, version, target, response.body.size)
        return target
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
