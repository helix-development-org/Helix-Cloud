package org.helix.node.services

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.tasks.TaskStore

class ServiceManagerTest {
    private val paths = NodePaths(createTempDirectory("helix")).createAll()
    private val taskStore = TaskStore(paths.tasks)
    private val executor = FakeExecutor()
    private val fakeJar = Files.write(paths.cache.resolve("fake.jar"), byteArrayOf(1))
    private val preparer = WorkspacePreparer(
        paths = paths,
        internalResources = { ByteArrayInputStream(byteArrayOf(7)) },
        serverJar = { _, _ -> fakeJar },
        eulaAccepted = true,
    )
    private val manager = ServiceManager(
        taskStore = taskStore,
        workspacePreparer = preparer,
        executors = mapOf(ExecutorType.PROCESS to executor),
        environmentProvider = { mapOf("HELIX_CONTROL_URL" to "http://127.0.0.1:8080") },
        clock = { 1000L },
    )

    private fun task(
        name: String = "Lobby",
        static: Boolean = false,
        maxServices: Int = 3,
    ): TaskDefinition = TaskDefinition(
        name = name,
        environment = Environment.PAPER,
        version = "1.21.11",
        staticServices = static,
        minServiceCount = 1,
        maxServiceCount = maxServices,
        startPort = 30000,
    ).also(taskStore::save)

    @Test
    fun `start prepares workspace and allocates ids and ports`() {
        task()

        val first = manager.startService("Lobby")
        val second = manager.startService("Lobby")

        assertEquals("Lobby-1", first.id)
        assertEquals("Lobby-2", second.id)
        assertEquals(30000, first.port)
        assertEquals(30001, second.port)
        assertEquals(ServiceState.STARTING, first.state)
        assertTrue(Files.exists(paths.servicesTemp.resolve("Lobby-1/wrapper.properties")))
        assertTrue(Files.exists(paths.servicesTemp.resolve("Lobby-1/plugins/HelixPaperBridge.jar")))
        assertEquals("Lobby-1", executor.started.first().environmentVariables["HELIX_SERVICE_ID"])
        assertEquals(
            "http://127.0.0.1:8080",
            executor.started.first().environmentVariables["HELIX_CONTROL_URL"],
        )
    }

    @Test
    fun `max service count is enforced`() {
        task(maxServices = 1)
        manager.startService("Lobby")

        assertFailsWith<IllegalArgumentException> { manager.startService("Lobby") }
    }

    @Test
    fun `requested stop ends in STOPPED and temp workspace is deleted`() {
        task()
        val info = manager.startService("Lobby")
        val workspace = paths.servicesTemp.resolve(info.id)

        assertTrue(manager.stopService(info.id))
        assertTrue(executor.handles.first().stopCalled)
        executor.handles.first().exit(0)

        assertNull(manager.find(info.id))
        assertFalse(Files.exists(workspace))
    }

    @Test
    fun `crashed dynamic service stays visible with captured logs and frees its id`() {
        task()
        val info = manager.startService("Lobby")
        val workspace = paths.servicesTemp.resolve(info.id)

        executor.handles.first().exit(1)

        val failed = manager.find(info.id)!!
        assertEquals(ServiceState.FAILED, failed.state)
        assertEquals(listOf("log line"), manager.logs(info.id, 10))
        assertFalse(Files.exists(workspace))

        val restarted = manager.startService("Lobby")
        assertEquals("Lobby-1", restarted.id)
    }

    @Test
    fun `unexpected exit ends in FAILED`() {
        task(name = "Static", static = true)
        val info = manager.startService("Static")

        executor.handles.first().exit(1)

        assertEquals(ServiceState.FAILED, manager.find(info.id)?.state)
        assertTrue(Files.exists(paths.servicesStatic.resolve(info.id)))
    }

    @Test
    fun `static service keeps workspace and id across restarts`() {
        task(name = "Static", static = true)
        val first = manager.startService("Static")
        Files.writeString(paths.servicesStatic.resolve("Static-1/keep.txt"), "data")
        manager.stopService(first.id)
        executor.handles.first().exit(0)

        val second = manager.startService("Static")

        assertEquals("Static-1", second.id)
        assertEquals("data", Files.readString(paths.servicesStatic.resolve("Static-1/keep.txt")))
    }

    @Test
    fun `heartbeat moves service to RUNNING and tracks players`() {
        task()
        val info = manager.startService("Lobby")

        assertTrue(manager.handleHeartbeat(HeartbeatReport(info.id, onlinePlayers = 5, maxPlayers = 64)))

        val managed = manager.find(info.id)!!
        assertEquals(ServiceState.RUNNING, managed.state)
        assertEquals(5, managed.onlinePlayers)
        assertEquals(64, managed.maxPlayers)
        assertNull(managed.emptySinceEpochMs)

        manager.handleHeartbeat(HeartbeatReport(info.id, onlinePlayers = 0, maxPlayers = 64))
        assertEquals(1000L, managed.emptySinceEpochMs)
    }

    @Test
    fun `terminated listener fires`() {
        task()
        var terminated: ManagedService? = null
        manager.onServiceTerminated { terminated = it }
        val info = manager.startService("Lobby")

        executor.handles.first().exit(0)

        assertEquals(info.id, terminated?.id)
    }

    @Test
    fun `unknown heartbeat is rejected`() {
        assertFalse(manager.handleHeartbeat(HeartbeatReport("Ghost-1", 0, 0)))
    }

    @Test
    fun `watchdog kill always settles FAILED even on a clean exit code`() {
        task()
        val info = manager.startService("Lobby")

        assertTrue(manager.watchdogFail(info.id, "stuck"))
        assertTrue(executor.handles.first().killCalled)
        executor.handles.first().exit(0)

        assertEquals(ServiceState.FAILED, manager.find(info.id)?.state)
    }

    @Test
    fun `watchdog kill on an unknown or inactive service is a no-op`() {
        assertFalse(manager.watchdogFail("Ghost-1", "stuck"))

        task(name = "Static", static = true)
        val info = manager.startService("Static")
        executor.handles.first().exit(0)
        assertEquals(ServiceState.STOPPED, manager.find(info.id)?.state)

        assertFalse(manager.watchdogFail(info.id, "stuck"))
    }

    @Test
    fun `stop and kill on a terminated service never re-enter STOPPING`() {
        task(name = "Static", static = true)
        val info = manager.startService("Static")
        executor.handles.first().exit(0)

        assertFalse(manager.stopService(info.id))
        assertFalse(manager.killService(info.id))
        assertEquals(ServiceState.STOPPED, manager.find(info.id)?.state)
    }

    @Test
    fun `late exit of a superseded service leaves the successor untouched`() {
        task()
        val info = manager.startService("Lobby")
        val old = manager.find(info.id)!!

        // simulate a stop whose exit callback is delayed: the coordinator
        // already sees the service as inactive and starts a replacement
        old.stopRequested = true
        old.state = ServiceState.STOPPED
        val successorInfo = manager.startService("Lobby")
        assertEquals(info.id, successorInfo.id) // the id got reused
        val successor = manager.find(info.id)!!

        // now the predecessor's exit callback finally fires
        executor.handles.first().exit(0)

        // the successor keeps its map slot and its freshly prepared workspace
        assertTrue(manager.find(info.id) === successor)
        assertEquals(ServiceState.STARTING, manager.find(info.id)?.state)
        assertTrue(Files.exists(paths.servicesTemp.resolve("${info.id}/wrapper.properties")))
    }

    @Test
    fun `port probe does not hold the manager lock`() {
        task()
        val probing = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val probingManager = ServiceManager(
            taskStore = taskStore,
            workspacePreparer = preparer,
            executors = mapOf(ExecutorType.PROCESS to executor),
            portAllocator = PortAllocator(canBind = { probing.countDown(); releaseProbe.await(); true }),
        )
        val starter = Thread { probingManager.startService("Lobby") }.apply { start() }
        try {
            assertTrue(probing.await(5, TimeUnit.SECONDS), "port probe never started")

            // a reader must not stall behind the (potentially slow) bind probe
            val readerDone = CountDownLatch(1)
            Thread { probingManager.services(); readerDone.countDown() }.start()
            assertTrue(readerDone.await(2, TimeUnit.SECONDS), "reader blocked behind the port probe")
        } finally {
            releaseProbe.countDown()
            starter.join(5_000)
        }
        assertEquals(30000, probingManager.services().single().port)
    }
}
