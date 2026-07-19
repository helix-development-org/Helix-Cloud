package org.helix.node.scaling

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.task.AutoScaleSettings
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.services.FakeExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.WorkspacePreparer
import org.helix.node.tasks.TaskStore

class AutoScalerTest {
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
        ),
        executors = mapOf(ExecutorType.PROCESS to executor),
        clock = { now },
    )
    private val scaler = AutoScaler(taskStore, manager, clock = { now })

    private fun task(min: Int, max: Int, threshold: Double = 0.8, idleSeconds: Long = 60) {
        taskStore.save(
            TaskDefinition(
                name = "Game",
                environment = Environment.PAPER,
                version = "1.21.11",
                minServiceCount = min,
                maxServiceCount = max,
                maxPlayers = 10,
                startPort = 30000,
                autoScale = AutoScaleSettings(
                    enabled = true,
                    playerRatioThreshold = threshold,
                    idleStopSeconds = idleSeconds,
                ),
            ),
        )
    }

    @Test
    fun `tick keeps minimum service count`() {
        task(min = 2, max = 4)

        scaler.tick()

        assertEquals(2, manager.activeCount("Game"))
    }

    @Test
    fun `minimum is restored after a service terminates`() {
        task(min = 1, max = 2)
        scaler.tick()
        executor.handles.first().exit(1)

        scaler.tick()

        assertEquals(1, manager.activeCount("Game"))
    }

    @Test
    fun `full services trigger scale up until max`() {
        task(min = 1, max = 2)
        scaler.tick()
        manager.handleHeartbeat(HeartbeatReport("Game-1", onlinePlayers = 9, maxPlayers = 10))

        scaler.tick()
        assertEquals(2, manager.activeCount("Game"))

        manager.handleHeartbeat(HeartbeatReport("Game-2", onlinePlayers = 10, maxPlayers = 10))
        manager.handleHeartbeat(HeartbeatReport("Game-1", onlinePlayers = 10, maxPlayers = 10))
        scaler.tick()

        assertEquals(2, manager.activeCount("Game"))
    }

    @Test
    fun `idle surplus service is stopped after timeout`() {
        task(min = 1, max = 3, idleSeconds = 60)
        scaler.tick()
        manager.handleHeartbeat(HeartbeatReport("Game-1", onlinePlayers = 10, maxPlayers = 10))
        scaler.tick()
        assertEquals(2, manager.activeCount("Game"))

        now = 10_000
        manager.handleHeartbeat(HeartbeatReport("Game-1", onlinePlayers = 1, maxPlayers = 10))
        manager.handleHeartbeat(HeartbeatReport("Game-2", onlinePlayers = 0, maxPlayers = 10))
        scaler.tick()
        assertEquals(2, manager.activeCount("Game"))

        now = 80_000
        scaler.tick()

        assertTrue(executor.handles[1].stopCalled)
        executor.handles[1].exit(0)
        assertEquals(1, manager.activeCount("Game"))
    }

    @Test
    fun `below threshold no scale up happens`() {
        task(min = 1, max = 3)
        scaler.tick()
        manager.handleHeartbeat(HeartbeatReport("Game-1", onlinePlayers = 2, maxPlayers = 10))

        scaler.tick()

        assertEquals(1, manager.activeCount("Game"))
    }
}
