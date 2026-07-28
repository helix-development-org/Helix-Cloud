package org.helix.node.launcher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelixDirectoryInitializerTest {
    @Test
    fun `creates directory layout and default configuration`() {
        val root = createTempDirectory("helix").resolve("Helix")

        HelixDirectoryInitializer(root).initialize()

        listOf("config", "tasks", "templates", "services/static", "services/temp", "cache", "addons").forEach {
            assertTrue(Files.isDirectory(root.resolve(it)), "missing directory: $it")
        }
        // the well-known default token must never be shipped: a strong random one is generated instead
        val nodeToml = root.resolve("config/node.toml").readText()
        assertFalse(nodeToml.contains("dev-token-change-me"))
        val token = Regex("""token = "([0-9a-f]+)"""").find(nodeToml)?.groupValues?.get(1)
        assertTrue(token != null && token.length >= 64, "expected a strong generated token, got: $token")
        assertTrue(root.resolve("config/versions.toml").readText().contains("[[paper]]"))
    }

    @Test
    fun `each initialize generates a different random token`() {
        val tokenOf = { root: java.nio.file.Path ->
            HelixDirectoryInitializer(root).initialize()
            Regex("""token = "([0-9a-f]+)"""").find(root.resolve("config/node.toml").readText())!!.groupValues[1]
        }
        val first = tokenOf(createTempDirectory("helix").resolve("Helix"))
        val second = tokenOf(createTempDirectory("helix").resolve("Helix"))

        assertTrue(first != second)
    }

    @Test
    fun `generated node toml matches the code defaults exactly, apart from the generated token`() {
        val root = createTempDirectory("helix").resolve("Helix")
        HelixDirectoryInitializer(root).initialize()

        // Loading the generated file must yield the built-in defaults (except the
        // randomly generated token) — this guards against the template drifting
        // from NodeConfig.
        val loaded = org.helix.node.config.NodeConfigLoader().load(root)

        assertEquals(org.helix.node.config.NodeConfig().control.copy(token = loaded.control.token), loaded.control)
        assertEquals(org.helix.node.config.NodeConfig().docker, loaded.docker)
        assertEquals(org.helix.node.config.NodeConfig().storage, loaded.storage)
        assertEquals(org.helix.node.config.NodeConfig().network, loaded.network)
        assertEquals(org.helix.node.config.NodeConfig().proxy.copy(forwardingSecret = loaded.proxy.forwardingSecret), loaded.proxy)
        assertEquals(org.helix.node.config.NodeConfig().eula, loaded.eula)
    }

    @Test
    fun `generates a random forwarding secret distinct from the admin token, eula not accepted by default`() {
        val root = createTempDirectory("helix").resolve("Helix")
        HelixDirectoryInitializer(root).initialize()

        val loaded = org.helix.node.config.NodeConfigLoader().load(root)

        assertTrue(loaded.proxy.forwardingSecret.length >= 64, "expected a strong generated forwarding secret")
        assertTrue(loaded.proxy.forwardingSecret != loaded.control.token, "secret must not reuse the admin token")
        assertFalse(loaded.proxy.legacyForwarding)
        assertFalse(loaded.eula.accept)
    }

    @Test
    fun `never overwrites existing configuration`() {
        val root = createTempDirectory("helix").resolve("Helix")
        val initializer = HelixDirectoryInitializer(root)
        initializer.initialize()
        root.resolve("config/node.toml").writeText("custom")

        initializer.initialize()

        assertEquals("custom", root.resolve("config/node.toml").readText())
    }
}
