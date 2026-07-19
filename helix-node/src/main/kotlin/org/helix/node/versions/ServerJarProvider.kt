package org.helix.node.versions

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.helix.api.environment.Environment
import org.slf4j.LoggerFactory

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
     * @param environment platform to provide.
     * @param version platform version to provide.
     * @return path of the cached jar.
     * @throws IllegalArgumentException if the download fails.
     */
    fun ensureJar(environment: Environment, version: String): Path {
        val target = cacheDirectory.resolve("${environment.name.lowercase()}-$version.jar")
        if (Files.exists(target)) {
            return target
        }
        Files.createDirectories(cacheDirectory)
        val override = catalog.find(environment, version)?.url
        val uri = override?.let(URI::create) ?: resolver.resolve(environment, version)
        logger.info("Downloading {} {} from {}", environment, version, uri)
        val response = fetcher.get(uri, mapOf("User-Agent" to "helix-cloud"))
        require(response.statusCode in 200..299) {
            "download of $environment $version failed with HTTP ${response.statusCode}"
        }
        val temp = Files.createTempFile(cacheDirectory, "download", ".part")
        Files.write(temp, response.body)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        logger.info("Cached {} {} at {} ({} bytes)", environment, version, target, response.body.size)
        return target
    }
}
