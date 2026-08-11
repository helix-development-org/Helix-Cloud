package org.helix.node.platform

import org.helix.api.bridge.HeartbeatReport
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.services.FakeExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.WorkspacePreparer
import org.helix.node.tasks.TaskStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeartbeatWatchdogTest {
    private var now = 0L
    private val paths = NodePaths(createTempDirectory("helix")).createAll()
    private val taskStore = TaskStore(paths.tasks)
    private val executor = FakeExecutor()
    private val fakeJar = Files.write(paths.cache.resolve("fake.jar"), byteArrayOf(1))
    private val manager = ServiceManager(
        taskStore = taskStore,
        workspacePreparer = WorkspacePreparer(
            paths = paths,
            internalResources = { ByteArrayInputStream(byteArrayOf(1)) },
            serverJar = { _, _ -> fakeJar },
            eulaAccepted = true,
        ),
        executors = mapOf(ExecutorType.PROCESS to executor),
        clock = { now },
    )
    private val watchdog = HeartbeatWatchdog(
        manager,
        startDeadlineMillis = 120_000L,
        staleThresholdMillis = 60_000L,
        clock = { now },
    )

    private fun task() = TaskDefinition(
        name = "Lobby",
        environment = Environment.PAPER,
        version = "1.21.11",
        maxServiceCount = 3,
        startPort = 30000,
    ).also(taskStore::save)

    @Test
    fun `service stuck in STARTING past the deadline is killed and settles FAILED`() {
        task()
        val info = manager.startService("Lobby")

        now = 119_000L
        watchdog.tick()
        assertFalse(executor.handles.first().killCalled, "must not reap before the deadline")

        now = 121_000L
        watchdog.tick()
        assertTrue(executor.handles.first().killCalled)

        executor.handles.first().exit(137)
        assertEquals(ServiceState.FAILED, manager.find(info.id)?.state)
    }

    @Test
    fun `running service with no heartbeat past the threshold is killed and settles FAILED`() {
        task()
        val info = manager.startService("Lobby")
        manager.handleHeartbeat(HeartbeatReport(info.id, onlinePlayers = 0, maxPlayers = 10))
        assertEquals(ServiceState.RUNNING, manager.find(info.id)?.state)

        now = 59_000L
        watchdog.tick()
        assertFalse(executor.handles.first().killCalled, "must not reap before the stale threshold")

        now = 61_000L
        watchdog.tick()
        assertTrue(executor.handles.first().killCalled)
        // no longer RUNNING the instant the watchdog reaps it, so routing (which
        // filters on RUNNING) excludes it before the process has even exited
        assertEquals(ServiceState.STOPPING, manager.find(info.id)?.state)

        executor.handles.first().exit(1)
        assertEquals(ServiceState.FAILED, manager.find(info.id)?.state)
    }

    @Test
    fun `running service with a fresh heartbeat is left alone`() {
        task()
        val info = manager.startService("Lobby")
        manager.handleHeartbeat(HeartbeatReport(info.id, onlinePlayers = 0, maxPlayers = 10))

        now = 61_000L
        manager.handleHeartbeat(HeartbeatReport(info.id, onlinePlayers = 0, maxPlayers = 10))
        watchdog.tick()

        assertFalse(executor.handles.first().killCalled)
        assertEquals(ServiceState.RUNNING, manager.find(info.id)?.state)
    }
}
