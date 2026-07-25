package org.helix.node.services

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.api.environment.Environment
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths

class WorkspacePreparerTest {
    private val paths = NodePaths(createTempDirectory("helix")).createAll()
    private val fakeJar = Files.write(paths.cache.resolve("fake.jar"), byteArrayOf(1, 2))
    private val preparer = WorkspacePreparer(
        paths = paths,
        internalResources = { name -> ByteArrayInputStream(name.toByteArray()) },
        serverJar = { _, _ -> fakeJar },
    )

    @Test
    fun `prepares paper workspace with wrapper bridge and defaults`() {
        val task = TaskDefinition(
            name = "Lobby",
            environment = Environment.PAPER,
            version = "1.21.11",
            maxPlayers = 42,
            templates = listOf("lobby"),
        )
        Files.createDirectories(paths.templates.resolve("lobby/data"))
        paths.templates.resolve("lobby/data/level.txt").writeText("world")

        val workspace = preparer.prepare(task, "Lobby-1", 30001)

        assertTrue(Files.exists(workspace.resolve("Wrapper.jar")))
        assertTrue(Files.exists(workspace.resolve("server.jar")))
        assertTrue(Files.exists(workspace.resolve("plugins/HelixPaperBridge.jar")))
        assertEquals("world", workspace.resolve("data/level.txt").readText())
        assertTrue(workspace.resolve("server.properties").readText().contains("server-port=30001"))
        assertTrue(workspace.resolve("server.properties").readText().contains("max-players=42"))
        assertTrue(workspace.resolve("eula.txt").readText().contains("eula=true"))
        assertTrue(workspace.resolve("spigot.yml").readText().contains("bungeecord: true"))
        assertTrue(workspace.resolve("wrapper.properties").readText().contains("serverArgs=--nogui"))
    }

    @Test
    fun `prepares velocity workspace with velocity toml`() {
        val task = TaskDefinition(
            name = "Proxy",
            environment = Environment.VELOCITY,
            version = "3.4.0",
            startPort = 25577,
        )

        val workspace = preparer.prepare(task, "Proxy-1", 25577)

        assertTrue(Files.exists(workspace.resolve("plugins/HelixVelocityBridge.jar")))
        val velocityToml = workspace.resolve("velocity.toml").readText()
        assertTrue(velocityToml.contains("bind = \"0.0.0.0:25577\""))
        assertTrue(velocityToml.contains("config-version = \"2.7\""))
        assertTrue(velocityToml.contains("[forced-hosts]"))
        assertFalse(Files.exists(workspace.resolve("eula.txt")))
    }

    @Test
    fun `static workspace keeps platform config but refreshes node files`() {
        val task = TaskDefinition(
            name = "Build",
            environment = Environment.PAPER,
            version = "1.21.11",
            staticServices = true,
        )
        val workspace = preparer.prepare(task, "Build-1", 30005)
        workspace.resolve("server.properties").writeText("custom")
        workspace.resolve("Wrapper.jar").writeText("stale")

        preparer.prepare(task, "Build-1", 30005)

        assertEquals("custom", workspace.resolve("server.properties").readText())
        assertEquals("helix-internal/Wrapper.jar", workspace.resolve("Wrapper.jar").readText())
    }

    @Test
    fun `dynamic workspace starts fresh every time`() {
        val task = TaskDefinition(name = "Game", environment = Environment.PAPER, version = "1.21.11")
        val workspace = preparer.prepare(task, "Game-1", 30010)
        workspace.resolve("leftover.txt").writeText("old")

        preparer.prepare(task, "Game-1", 30010)

        assertFalse(Files.exists(workspace.resolve("leftover.txt")))
    }

    @Test
    fun `installs addon paper components and removes stale ones`() {
        val component = Files.write(paths.root.resolve("bettermsgs-paper.jar"), byteArrayOf(7))
        var active = listOf("helix.bettermsgs" to component)
        val componentPreparer = WorkspacePreparer(
            paths = paths,
            internalResources = { name -> ByteArrayInputStream(name.toByteArray()) },
            serverJar = { _, _ -> fakeJar },
            paperComponents = { active },
        )
        val task = TaskDefinition(
            name = "Lobby",
            environment = Environment.PAPER,
            version = "1.21.11",
            staticServices = true,
        )

        val workspace = componentPreparer.prepare(task, "Lobby-1", 30001)
        assertTrue(Files.exists(workspace.resolve("plugins/HelixAddon-helix.bettermsgs.jar")))

        // addon disabled → the component disappears on the next prepare
        active = emptyList()
        componentPreparer.prepare(task, "Lobby-1", 30001)
        assertFalse(Files.exists(workspace.resolve("plugins/HelixAddon-helix.bettermsgs.jar")))
        // the bridge itself is untouched
        assertTrue(Files.exists(workspace.resolve("plugins/HelixPaperBridge.jar")))
    }

    @Test
    fun `port allocator skips used ports`() {
        val allocator = PortAllocator()

        assertEquals(30000, allocator.allocate(30000, emptySet()))
        assertEquals(30002, allocator.allocate(30000, setOf(30000, 30001)))
    }
}
