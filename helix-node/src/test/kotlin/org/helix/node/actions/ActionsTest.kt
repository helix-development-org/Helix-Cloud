package org.helix.node.actions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.execution.ExecutorType
import org.helix.node.cli.NodeCli
import org.helix.node.launcher.NodePaths
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.services.FakeExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.WorkspacePreparer
import org.helix.node.tasks.TaskStore
import org.helix.node.versions.VersionCatalog

class ActionsTest {
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
        executors = mapOf(ExecutorType.PROCESS to executor, ExecutorType.DOCKER to executor),
    )
    private val routing = ProxyRoutingService(manager)
    private val registry = ActionRegistry()
    private var shutdownCalled = false

    init {
        BuiltinActions(
            paths = paths,
            taskStore = taskStore,
            manager = manager,
            routing = routing,
            overviewService = PlatformOverviewService("1.0.0", taskStore, manager),
            versionCatalog = { VersionCatalog(emptyList()) },
            shutdown = { shutdownCalled = true },
        ).registerAll(registry)
    }

    private fun invoke(action: String, vararg arguments: String): ActionResult =
        registry.invoke(ActionInvocation(action, arguments.toList()))

    @Test
    fun `unknown action fails cleanly`() {
        val result = invoke("nope")

        assertFalse(result.success)
        assertTrue(result.lines.first().contains("unknown action"))
    }

    @Test
    fun `task create start stop delete lifecycle works through actions`() {
        val created = invoke("task.create", "Lobby", "paper", "1.21.11", "max=2", "autoscale=true")
        assertTrue(created.success, created.lines.joinToString())
        assertTrue(Files.isDirectory(paths.templates.resolve("Lobby")))
        assertEquals(true, taskStore.find("Lobby")?.autoScale?.enabled)

        val started = invoke("service.start", "Lobby")
        assertTrue(started.success)
        assertTrue(started.lines.first().contains("Lobby-1"))

        val blocked = invoke("task.delete", "Lobby")
        assertFalse(blocked.success)

        assertTrue(invoke("service.stop", "Lobby-1").success)
        executor.handles.first().exit(0)
        assertTrue(invoke("task.delete", "Lobby").success)
    }

    @Test
    fun `task create rejects blank version`() {
        val result = invoke("task.create", "Proxy", "velocity", "")

        assertFalse(result.success)
        assertTrue(result.lines.first().contains("version must not be blank"))
    }

    @Test
    fun `duplicate task create is rejected`() {
        invoke("task.create", "Lobby", "paper", "1.21.11")

        assertFalse(invoke("task.create", "Lobby", "paper", "1.21.11").success)
    }

    @Test
    fun `maintenance toggles through action`() {
        assertTrue(invoke("proxy.maintenance", "on").success)
        assertTrue(routing.maintenance)
        assertTrue(invoke("proxy.maintenance").lines.first().contains("on"))
        assertTrue(invoke("proxy.maintenance", "off").success)
        assertFalse(routing.maintenance)
    }

    @Test
    fun `handler exceptions become failed results`() {
        registry.register(ActionDescriptor("boom", "explodes", "boom")) { error("kaputt") }

        val result = registry.invoke(ActionInvocation("boom"))

        assertFalse(result.success)
        assertTrue(result.lines.first().contains("kaputt"))
    }

    @Test
    fun `platform stop wires shutdown`() {
        assertTrue(invoke("platform.stop").success)
        assertTrue(shutdownCalled)
    }

    @Test
    fun `actions list contains registered actions`() {
        val listed = invoke("actions.list").lines.joinToString("\n")

        assertTrue(listed.contains("task.create"))
        assertTrue(listed.contains("service.logs"))
    }

    @Test
    fun `cli routes lines through actions and stops on exit`() {
        val out = ByteArrayOutputStream()
        val cli = NodeCli(registry, PrintStream(out))

        assertTrue(cli.handle("task.create Lobby paper 1.21.11"))
        assertTrue(cli.handle("service.list"))
        assertTrue(cli.handle(""))
        assertFalse(cli.handle("exit"))

        val printed = out.toString()
        assertTrue(printed.contains("created task Lobby"))
        assertNotNull(taskStore.find("Lobby"))
        assertTrue(shutdownCalled)
    }
}
