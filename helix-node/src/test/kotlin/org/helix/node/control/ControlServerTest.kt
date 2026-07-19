package org.helix.node.control

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.platform.PlatformOverview
import org.helix.api.proxy.RoutingSnapshot
import org.helix.api.service.ServiceInfo
import org.helix.api.task.TaskDefinition
import org.helix.node.actions.ActionRegistry
import org.helix.node.actions.BuiltinActions
import org.helix.node.addons.AddonManager
import org.helix.node.launcher.NodePaths
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.services.FakeExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.WorkspacePreparer
import org.helix.node.tasks.TaskStore
import org.helix.node.versions.VersionCatalog

class ControlServerTest {
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
    private val dependencies = ControlDependencies(
        token = "secret",
        registry = registry,
        taskStore = taskStore,
        manager = manager,
        routing = routing,
        overviewService = PlatformOverviewService("1.0.0", taskStore, manager),
        addonManager = AddonManager(paths.addons, registry),
    )

    init {
        BuiltinActions(
            paths = paths,
            taskStore = taskStore,
            manager = manager,
            routing = routing,
            overviewService = PlatformOverviewService("1.0.0", taskStore, manager),
            versionCatalog = { VersionCatalog(emptyList()) },
            shutdown = {},
        ).registerAll(registry)
    }

    private fun ApplicationTestBuilder.apiClient(): HttpClient {
        application { controlModule(dependencies) }
        return createClient {
            install(ContentNegotiation) { json() }
        }
    }

    private val lobby = TaskDefinition(
        name = "Lobby",
        environment = Environment.PAPER,
        version = "1.21.11",
        maxServiceCount = 2,
        startPort = 30000,
    )

    @Test
    fun `api requires bearer token`() = testApplication {
        val client = apiClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/tasks").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/tasks") { bearerAuth("secret") }.status,
        )
    }

    @Test
    fun `task crud and service lifecycle over rest`() = testApplication {
        val client = apiClient()

        val put = client.put("/api/v1/tasks/Lobby") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(lobby)
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val tasks: List<TaskDefinition> = client.get("/api/v1/tasks") { bearerAuth("secret") }.body()
        assertEquals(listOf(lobby), tasks)

        val started: ServiceInfo = client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }.body()
        assertEquals("Lobby-1", started.id)

        val heartbeat = client.post("/api/v1/internal/heartbeat") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(HeartbeatReport("Lobby-1", 7, 100))
        }
        assertEquals(HttpStatusCode.OK, heartbeat.status)

        val overview: PlatformOverview = client.get("/api/v1/platform/overview") { bearerAuth("secret") }.body()
        assertEquals(7, overview.onlinePlayers)

        val snapshot: RoutingSnapshot = client.get("/api/v1/internal/routing?proxyServiceId=x") {
            bearerAuth("secret")
        }.body()
        assertEquals("Lobby-1", snapshot.backends.single().serviceId)

        val blockedDelete = client.delete("/api/v1/tasks/Lobby") { bearerAuth("secret") }
        assertEquals(HttpStatusCode.BadRequest, blockedDelete.status)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/v1/services/Lobby-1/stop") { bearerAuth("secret") }.status,
        )
        executor.handles.first().exit(0)

        assertEquals(
            HttpStatusCode.OK,
            client.delete("/api/v1/tasks/Lobby") { bearerAuth("secret") }.status,
        )
    }

    @Test
    fun `actions are invocable over rest`() = testApplication {
        val client = apiClient()

        val result: ActionResult = client.post("/api/v1/actions") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(ActionInvocation("task.create", listOf("Game", "paper", "1.21.11")))
        }.body()

        assertTrue(result.success, result.lines.joinToString())
        assertEquals("Game", taskStore.find("Game")?.name)
    }

    @Test
    fun `unknown service yields 404`() = testApplication {
        val client = apiClient()

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/services/Ghost-1") { bearerAuth("secret") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/api/v1/services/Ghost-1/stop") { bearerAuth("secret") }.status,
        )
    }

    @Test
    fun `addons endpoint lists installed addons`() = testApplication {
        val client = apiClient()

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/addons") { bearerAuth("secret") }.status)
    }

    @Test
    fun `dashboard is served without authentication`() = testApplication {
        val client = apiClient()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<String>().contains("Helix-Cloud"))
    }
}
