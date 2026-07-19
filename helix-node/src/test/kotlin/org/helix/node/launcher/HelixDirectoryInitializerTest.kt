package org.helix.node.launcher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelixDirectoryInitializerTest {
    @Test
    fun `creates directory layout and default configuration`() {
        val root = createTempDirectory("helix").resolve("Helix")

        HelixDirectoryInitializer(root).initialize()

        listOf("config", "tasks", "templates", "services/static", "services/temp", "cache", "addons").forEach {
            assertTrue(Files.isDirectory(root.resolve(it)), "missing directory: $it")
        }
        assertTrue(root.resolve("config/node.toml").readText().contains("dev-token-change-me"))
        assertTrue(root.resolve("config/versions.toml").readText().contains("[[paper]]"))
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
