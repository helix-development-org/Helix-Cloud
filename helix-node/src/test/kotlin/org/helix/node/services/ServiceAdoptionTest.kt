package org.helix.node.services

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.tasks.TaskStore

class ServiceAdoptionTest {
    private val paths = NodePaths(createTempDirectory("helix")).createAll()
    private val taskStore = TaskStore(paths.tasks)
    private val executor = FakeExecutor()
    private val fakeJar = Files.write(paths.cache.resolve("fake.jar"), byteArrayOf(1))
    private val registryFile = ServiceRegistryFile(paths.root.resolve("services/registry.json"))

    private fun manager() = ServiceManager(
        taskStore = taskStore,
        workspacePreparer = WorkspacePreparer(
            paths = paths,
            internalResources = { ByteArrayInputStream(byteArrayOf(7)) },
            serverJar = { _, _ -> fakeJar },
        ),
        executors = mapOf(ExecutorType.PROCESS to executor),
        registry = registryFile,
    )

    private val task = TaskDefinition(
        name = "Lobby",
        environment = Environment.PAPER,
        version = "1.21.11",
        maxServiceCount = 3,
        startPort = 30000,
    ).also(taskStore::save)

    @Test
    fun `lifecycle changes mirror into the registry file`() {
        val manager = manager()
        manager.startService("Lobby")

        val started = registryFile.read().single()
        assertEquals("Lobby-1", started.id)
        assertEquals(ServiceState.STARTING, started.state)
        assertEquals(ExecutorType.PROCESS, started.executor)
        assertEquals(30000, started.port)

        manager.handleHeartbeat(HeartbeatReport("Lobby-1", 0, 20))
        assertEquals(ServiceState.RUNNING, registryFile.read().single().state)

        manager.stopService("Lobby-1")
        executor.handles.first().exit(0)
        // dynamic stopped services drop out of the map and the file
        assertTrue(registryFile.read().isEmpty())
    }

    @Test
    fun `a fresh manager adopts a surviving service from the registry entry`() {
        manager().startService("Lobby")
        val entry = registryFile.read().single()

        // simulate the successor node: fresh manager, re-attached handle
        val successor = manager()
        val handle = FakeHandle()
        val adopted = successor.adopt(task, entry, handle)

        assertEquals(ServiceState.RUNNING, adopted.state)
        assertEquals(1, successor.activeCount("Lobby"))
        assertTrue(successor.handleHeartbeat(HeartbeatReport("Lobby-1", 3, 20)))
        // ids and ports of adopted services are respected by new allocations
        val next = successor.startService("Lobby")
        assertEquals("Lobby-2", next.id)
        assertEquals(30001, next.port)
        // exits of adopted services flow through the normal lifecycle
        handle.exit(0)
        assertEquals(1, successor.activeCount("Lobby"))
    }
}
