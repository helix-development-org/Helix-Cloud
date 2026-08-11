package org.helix.node.proxy

import org.helix.api.bridge.HeartbeatReport
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.services.FakeExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.WorkspacePreparer
import org.helix.node.tasks.TaskStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyRoutingServiceTest {
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
        executors = mapOf(
            ExecutorType.PROCESS to executor,
            ExecutorType.DOCKER to executor,
        ),
    )
    private val routing = ProxyRoutingService(manager)

    private fun setup(lobbyExecutor: ExecutorType, proxyExecutor: ExecutorType) {
        taskStore.save(
            TaskDefinition(
                name = "Lobby",
                environment = Environment.PAPER,
                version = "1.21.11",
                executor = lobbyExecutor,
                startPort = 30000,
                maxServiceCount = 3,
                fallbackEligible = true,
            ),
        )
        taskStore.save(
            TaskDefinition(
                name = "Proxy",
                environment = Environment.VELOCITY,
                version = "3.4.0",
                executor = proxyExecutor,
                startPort = 25577,
            ),
        )
        manager.startService("Lobby")
        manager.startService("Proxy")
        manager.handleHeartbeat(HeartbeatReport("Lobby-1", 3, 100))
        manager.handleHeartbeat(HeartbeatReport("Proxy-1", 3, 500))
    }

    @Test
    fun `process proxy reaches process backend on loopback`() {
        setup(ExecutorType.PROCESS, ExecutorType.PROCESS)

        val backend = routing.snapshot("Proxy-1").backends.single()

        assertEquals("127.0.0.1", backend.host)
        assertEquals(30000, backend.port)
        assertTrue(backend.fallbackEligible)
    }

    @Test
    fun `docker proxy reaches docker backend via container name`() {
        setup(ExecutorType.DOCKER, ExecutorType.DOCKER)

        val backend = routing.snapshot("Proxy-1").backends.single()

        assertEquals("helix-lobby-1", backend.host)
    }

    @Test
    fun `docker proxy reaches process backend via host gateway`() {
        setup(ExecutorType.PROCESS, ExecutorType.DOCKER)

        assertEquals("host.docker.internal", routing.snapshot("Proxy-1").backends.single().host)
    }

    @Test
    fun `only running backends are routed and maintenance flag propagates`() {
        setup(ExecutorType.PROCESS, ExecutorType.PROCESS)
        manager.startService("Lobby")
        routing.maintenance = true

        val snapshot = routing.snapshot("Proxy-1")

        assertEquals(listOf("Lobby-1"), snapshot.backends.map { it.serviceId })
        assertTrue(snapshot.maintenance)
    }

    @Test
    fun `overview counts each player once via the proxy layer`() {
        setup(ExecutorType.PROCESS, ExecutorType.PROCESS)
        val overview = PlatformOverviewService("1.0.0", taskStore, manager).overview()

        assertEquals(2, overview.taskCount)
        assertEquals(2, overview.servicesRunning)
        // Lobby-1 and Proxy-1 both report 3 players — the SAME 3 players, seen once
        // on the backend and once on the proxy. The network total is 3, not 6.
        assertEquals(3, overview.onlinePlayers)
        assertEquals(100, overview.maxPlayers)
    }
}
