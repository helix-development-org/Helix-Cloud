package org.helix.node.services

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.environment.Environment
import org.helix.api.task.TaskDefinition

class ServiceRegistryFileTest {
    private val directory = createTempDirectory("helix-registry")
    private val file = directory.resolve("services/registry.json")
    private val registry = ServiceRegistryFile(file)

    private val task = TaskDefinition(
        name = "Lobby",
        environment = Environment.PAPER,
        version = "1.21.11",
        startPort = 30000,
    )

    private fun managed(id: String): ManagedService =
        ManagedService(id, task, directory.resolve(id), 30000)

    @Test
    fun `a missing file reads as an empty registry`() {
        assertEquals(emptyList(), registry.read())
    }

    @Test
    fun `an unparsable file reads as null instead of an empty registry`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ not json ]")

        // null (unknown survivors) must be distinguishable from empty (cold
        // boot), so boot code can skip the destructive orphan sweep
        assertNull(registry.read())
    }

    @Test
    fun `a stale snapshot never overwrites a newer one`() {
        val older = registry.nextSequence()
        val newer = registry.nextSequence()

        registry.write(newer, listOf(managed("Lobby-1")))
        registry.write(older, emptyList())

        assertEquals("Lobby-1", registry.read()!!.single().id)
    }

    @Test
    fun `snapshots written in sequence order replace each other`() {
        registry.write(registry.nextSequence(), listOf(managed("Lobby-1")))
        registry.write(registry.nextSequence(), emptyList())

        assertTrue(registry.read()!!.isEmpty())
    }

    @Test
    fun `control token round-trips through the file`() {
        val service = managed("Lobby-1").apply { controlToken = "token-123" }

        registry.write(registry.nextSequence(), listOf(service))

        assertEquals("token-123", registry.read()!!.single().controlToken)
    }
}
