package org.helix.node.versions

import java.net.URI
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionsTest {
    private val buildsJson = """
        [
          {"id": 10, "channel": "STABLE",
           "downloads": {"server:default": {"url": "https://example.org/paper-10.jar"}}},
          {"id": 12, "channel": "STABLE",
           "downloads": {"server:default": {"url": "https://example.org/paper-12.jar"}}},
          {"id": 13, "channel": "EXPERIMENTAL",
           "downloads": {"server:default": {"url": "https://example.org/paper-13.jar"}}}
        ]
    """.trimIndent()

    @Test
    fun `resolver picks newest stable build`() {
        val requested = mutableListOf<URI>()
        val resolver = PaperMcDownloadResolver(
            fetcher = { uri, _ ->
                requested += uri
                HttpFetchResponse(200, buildsJson.toByteArray())
            },
        )

        val uri = resolver.resolve(org.helix.api.environment.Environment.PAPER, "1.21.11")

        assertEquals(URI.create("https://example.org/paper-12.jar"), uri)
        assertTrue(requested.single().toString().endsWith("/projects/paper/versions/1.21.11/builds"))
    }

    @Test
    fun `resolver fails without stable build`() {
        val resolver = PaperMcDownloadResolver(
            fetcher = { _, _ -> HttpFetchResponse(200, "[]".toByteArray()) },
        )

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(org.helix.api.environment.Environment.VELOCITY, "3.4.0")
        }
    }

    @Test
    fun `catalog parses environments and overrides`() {
        val root = createTempDirectory("helix")
        Files.createDirectories(root.resolve("config"))
        root.resolve("config/versions.toml").writeText(
            """
            [[paper]]
            version = "1.21.11"

            [[velocity]]
            version = "3.4.0"
            url = "https://example.org/velocity.jar"
            """.trimIndent(),
        )

        val catalog = VersionCatalog.load(root)

        assertEquals(2, catalog.entries.size)
        assertEquals(
            "https://example.org/velocity.jar",
            catalog.find(org.helix.api.environment.Environment.VELOCITY, "3.4.0")?.url,
        )
        assertEquals("1.21.11", catalog.default(org.helix.api.environment.Environment.PAPER)?.version)
    }

    @Test
    fun `provider caches downloads and honors overrides`() {
        val cache = createTempDirectory("cache")
        val catalog = VersionCatalog(
            listOf(
                VersionEntry(
                    org.helix.api.environment.Environment.PAPER,
                    "1.21.11",
                    url = "https://example.org/direct.jar",
                ),
            ),
        )
        var downloads = 0
        val provider = ServerJarProvider(
            cacheDirectory = cache,
            catalog = catalog,
            resolver = PaperMcDownloadResolver(fetcher = { _, _ -> error("api must not be called") }),
            fetcher = { uri, _ ->
                downloads++
                assertEquals("https://example.org/direct.jar", uri.toString())
                HttpFetchResponse(200, byteArrayOf(1, 2, 3))
            },
        )

        val first = provider.ensureJar(org.helix.api.environment.Environment.PAPER, "1.21.11")
        val second = provider.ensureJar(org.helix.api.environment.Environment.PAPER, "1.21.11")

        assertEquals(first, second)
        assertEquals(1, downloads)
        assertEquals(3, Files.size(first))
    }
}
