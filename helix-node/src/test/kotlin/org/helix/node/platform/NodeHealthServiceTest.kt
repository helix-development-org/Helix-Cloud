package org.helix.node.platform

import org.helix.api.bridge.HeartbeatReport
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.task.TaskDefinition
import org.helix.node.gates.NativePermissionCache
import org.helix.node.launcher.NodePaths
import org.helix.node.players.PlayerRegistry
import org.helix.node.scheduler.JobScheduler
import org.helix.node.services.FakeExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.WorkspacePreparer
import org.helix.node.storage.JsonStorageProvider
import org.helix.node.tasks.TaskStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeHealthServiceTest {
    private val paths = NodePaths(createTempDirectory("helix")).createAll()
    private val taskStore = TaskStore(paths.tasks)
    private val executor = FakeExecutor()
    private val fakeJar = Files.write(paths.cache.resolve("fake.jar"), byteArrayOf(1))
    private var now = 100_000L
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
    private val players = PlayerRegistry()
    private val permissions = NativePermissionCache()
    private val jobs = JobScheduler(JsonStorageProvider().forAddon("scheduler", paths.root.resolve("s")), org.helix.node.actions.ActionRegistry())
    private val health = NodeHealthService(manager, players, permissions, jobs) { now }

    private fun task() = TaskDefinition(
        name = "Lobby",
        environment = Environment.PAPER,
        version = "1.21.11",
        maxServiceCount = 3,
        startPort = 30000,
    ).also(taskStore::save)

    @Test
    fun `snapshot reports the node process resources`() {
        val snapshot = health.snapshot()

        assertTrue(snapshot.heapMaxMb > 0)
        assertTrue(snapshot.threadCount > 0)
        assertTrue(snapshot.availableProcessors >= 1)
        assertTrue(snapshot.uptimeMs >= 0)
        assertEquals(0, snapshot.servicesTotal)
    }

    @Test
    fun `service resources aggregate running heartbeats`() {
        task()
        manager.startService("Lobby")
        manager.handleHeartbeat(
            HeartbeatReport("Lobby-1", 5, 100, memoryUsedMb = 512, memoryMaxMb = 2048, cpuPercent = 30.0),
        )

        val resources = health.serviceResources()
        assertEquals(512, resources.memoryUsedMb)
        assertEquals(2048, resources.memoryMaxMb)
        assertEquals(30.0, resources.cpuPercent)

        val snapshot = health.snapshot()
        assertEquals(1, snapshot.servicesRunning)
        assertEquals(512, snapshot.servicesMemoryUsedMb)
        assertEquals(0, snapshot.staleHeartbeats)
    }

    @Test
    fun `overdue heartbeats count as stale`() {
        task()
        manager.startService("Lobby")
        manager.handleHeartbeat(HeartbeatReport("Lobby-1", 0, 100))
        now += 60_000L

        assertEquals(1, health.snapshot().staleHeartbeats)
    }
}
