package org.helix.node.services

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.proxy.ProxyCommand
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.tasks.TaskStore

class RestartCoordinatorTest {
    private val paths = NodePaths(createTempDirectory("helix")).createAll()
    private val taskStore = TaskStore(paths.tasks)
    private val executor = FakeExecutor()
    private val fakeJar = Files.write(paths.cache.resolve("fake.jar"), byteArrayOf(1))
    private val manager = ServiceManager(
        taskStore = taskStore,
        workspacePreparer = WorkspacePreparer(
            paths = paths,
            internalResources = { ByteArrayInputStream(byteArrayOf(7)) },
            serverJar = { _, _ -> fakeJar },
            eulaAccepted = true,
        ),
        executors = mapOf(ExecutorType.PROCESS to executor),
    )
    private val delivered = CopyOnWriteArrayList<ProxyCommand>()
    private val coordinator = RestartCoordinator(
        manager = manager,
        deliver = delivered::add,
        stopWaitMillis = 2_000,
        scalerGraceMillis = 300,
    )

    private fun task() {
        taskStore.save(
            TaskDefinition(
                name = "Lobby",
                environment = Environment.PAPER,
                version = "1.21.11",
                maxServiceCount = 3,
                startPort = 30000,
            ),
        )
    }

    private fun awaitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(25)
        }
        assertTrue(condition(), "condition not met within ${timeoutMillis}ms")
    }

    @Test
    fun `restart announces stops the service and starts a replacement`() {
        task()
        manager.startService("Lobby")

        assertTrue(coordinator.restartService("Lobby-1", 1))

        awaitUntil { executor.handles.firstOrNull()?.stopCalled == true }
        executor.handles.first().exit(0)
        awaitUntil { executor.started.size == 2 }

        // countdown warning plus restart-now announcement, resolved per player
        val keys = delivered.mapNotNull { it.translationKey }
        assertTrue("helix.translations.network.restart.warn" in keys)
        assertTrue("helix.translations.network.restart.now" in keys)
        val warn = delivered.first { it.translationKey!!.endsWith("restart.warn") }
        assertEquals("Lobby-1", warn.params["target"])
        assertEquals("1", warn.params["seconds"])
        assertEquals("broadcast", warn.type)
    }

    @Test
    fun `task restart rolls over every active service`() {
        task()
        manager.startService("Lobby")
        manager.startService("Lobby")

        assertEquals(2, coordinator.restartTask("Lobby", 0))

        awaitUntil { executor.handles.firstOrNull()?.stopCalled == true }
        executor.handles[0].exit(0)
        awaitUntil { executor.handles.size >= 3 && executor.handles[1].stopCalled }
        executor.handles[1].exit(0)
        awaitUntil { executor.started.size == 4 }
    }

    @Test
    fun `unknown or stopped services are rejected`() {
        task()
        assertFalse(coordinator.restartService("Lobby-1", 0))
        assertEquals(0, coordinator.restartTask("Lobby", 0))
    }
}
