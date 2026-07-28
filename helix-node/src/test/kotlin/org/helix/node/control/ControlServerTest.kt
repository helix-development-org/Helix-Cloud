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
import io.ktor.client.statement.bodyAsText
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
import org.helix.api.action.ActionDescriptor
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
            eulaAccepted = true,
        ),
        executors = mapOf(ExecutorType.PROCESS to executor, ExecutorType.DOCKER to executor),
    )
    private val routing = ProxyRoutingService(manager)
    private val registry = ActionRegistry()
    private val eventLog = org.helix.node.events.EventLog()
    private val logBuffer = org.helix.node.logging.LogBuffer().apply { add("[main] INFO boot line") }
    private val networkPack = org.helix.node.packs.NetworkPackService(paths.root.resolve("packs"))
    private val dependencies = ControlDependencies(
        token = "secret",
        registry = registry,
        taskStore = taskStore,
        manager = manager,
        routing = routing,
        overviewService = PlatformOverviewService("1.0.0", taskStore, manager),
        addonManager = AddonManager(paths.addons, registry),
        logBuffer = logBuffer,
        eventLog = eventLog,
        networkPack = networkPack,
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
            // shares the same registry as ControlDependencies so `player.message`
            // (used by the panel login-code delivery) sees players marked online
            // via `dependencies.playerRegistry` in tests that exercise real login.
            playerRegistry = dependencies.playerRegistry,
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
            setBody(HeartbeatReport("Lobby-1", 7, 100, memoryUsedMb = 512, memoryMaxMb = 2048, cpuPercent = 12.5))
        }
        assertEquals(HttpStatusCode.OK, heartbeat.status)

        val overview: PlatformOverview = client.get("/api/v1/platform/overview") { bearerAuth("secret") }.body()
        assertEquals(7, overview.onlinePlayers)

        // resource metrics from the heartbeat surface on the service snapshot
        val withResources: ServiceInfo = client.get("/api/v1/services/Lobby-1") { bearerAuth("secret") }.body()
        assertEquals(512, withResources.memoryUsedMb)
        assertEquals(2048, withResources.memoryMaxMb)
        assertEquals(12.5, withResources.cpuPercent)

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
    fun `a per-service token authenticates its own service's internal routes only`() = testApplication {
        val client = apiClient()
        client.put("/api/v1/tasks/Lobby") {
            bearerAuth("secret"); contentType(ContentType.Application.Json); setBody(lobby)
        }
        client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }
        client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }
        val ownToken = dependencies.serviceTokens.mint("Lobby-1")

        // heartbeats and other internal routes for its OWN service id succeed
        val ownHeartbeat = client.post("/api/v1/internal/heartbeat") {
            bearerAuth(ownToken)
            contentType(ContentType.Application.Json)
            setBody(HeartbeatReport("Lobby-1", 3, 100))
        }
        assertEquals(HttpStatusCode.OK, ownHeartbeat.status)

        val ownRouting = client.get("/api/v1/internal/routing?proxyServiceId=Lobby-1") { bearerAuth(ownToken) }
        assertEquals(HttpStatusCode.OK, ownRouting.status)

        // a route that carries no service id at all (network-wide bridge info) is still reachable
        val nodes = client.get("/api/v1/internal/permission-nodes") { bearerAuth(ownToken) }
        assertEquals(HttpStatusCode.OK, nodes.status)
    }

    @Test
    fun `a per-service token is rejected for another service's heartbeat`() = testApplication {
        val client = apiClient()
        client.put("/api/v1/tasks/Lobby") {
            bearerAuth("secret"); contentType(ContentType.Application.Json); setBody(lobby)
        }
        client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }
        client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }
        val lobby1Token = dependencies.serviceTokens.mint("Lobby-1")

        val crossServiceHeartbeat = client.post("/api/v1/internal/heartbeat") {
            bearerAuth(lobby1Token)
            contentType(ContentType.Application.Json)
            setBody(HeartbeatReport("Lobby-2", 3, 100))
        }
        assertEquals(HttpStatusCode.Forbidden, crossServiceHeartbeat.status)

        val crossServiceRouting = client.get("/api/v1/internal/routing?proxyServiceId=Lobby-2") {
            bearerAuth(lobby1Token)
        }
        assertEquals(HttpStatusCode.Forbidden, crossServiceRouting.status)
    }

    @Test
    fun `a per-service token cannot reach task, addon or file management routes`() = testApplication {
        val client = apiClient()
        client.put("/api/v1/tasks/Lobby") {
            bearerAuth("secret"); contentType(ContentType.Application.Json); setBody(lobby)
        }
        client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }
        val token = dependencies.serviceTokens.mint("Lobby-1")

        // the per-service token DOES authenticate (it's a recognized bridge credential), but it
        // grants no permission node, so every admin/panel route answers 403, never 200
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/tasks") { bearerAuth(token) }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/v1/services/Lobby-1/stop") { bearerAuth(token) }.status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/addons") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/files/roots") { bearerAuth(token) }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/v1/actions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(ActionInvocation("task.create", listOf("Sneaky", "paper", "1.21.11")))
            }.status,
        )
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
    fun `join gate and proxy commands work over rest`() = testApplication {
        val client = apiClient()
        dependencies.joinGates.register("test") { request ->
            if (request.name == "steve") {
                org.helix.api.proxy.JoinDecision.deny("banned")
            } else {
                org.helix.api.proxy.JoinDecision.allow()
            }
        }

        val denied: org.helix.api.proxy.JoinDecision = client.post("/api/v1/internal/join-check") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(org.helix.api.proxy.JoinRequest("steve"))
        }.body()
        assertEquals(false, denied.allowed)

        val allowed: org.helix.api.proxy.JoinDecision = client.post("/api/v1/internal/join-check") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(org.helix.api.proxy.JoinRequest("alex"))
        }.body()
        assertEquals(true, allowed.allowed)

        dependencies.commandQueue.enqueue(listOf("Proxy-1"), org.helix.api.proxy.ProxyCommand.kick("steve", "banned"))
        val commands: List<org.helix.api.proxy.ProxyCommand> =
            client.get("/api/v1/internal/commands?proxyServiceId=Proxy-1") { bearerAuth("secret") }.body()
        assertEquals("steve", commands.single().player)

        dependencies.permissionResolvers.register("test") { request ->
            request.name == "steve" && request.permission == "helix.maintenance.bypass"
        }
        val granted: org.helix.api.proxy.PermissionDecision = client.post("/api/v1/internal/permission-check") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(org.helix.api.proxy.PermissionCheckRequest("steve", "helix.maintenance.bypass"))
        }.body()
        assertEquals(true, granted.allowed)
        val deniedPerm: org.helix.api.proxy.PermissionDecision = client.post("/api/v1/internal/permission-check") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(org.helix.api.proxy.PermissionCheckRequest("alex", "helix.maintenance.bypass"))
        }.body()
        assertEquals(false, deniedPerm.allowed)
    }

    @Test
    fun `a reported join feeds the identity registry`() = testApplication {
        val client = apiClient()

        val response = client.post("/api/v1/internal/player-event") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(org.helix.api.player.PlayerEvent("join", "Steve", "uuid-1"))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        assertEquals("uuid-1", dependencies.identityRegistry.resolveUuid("steve"))
        assertEquals("steve", dependencies.identityRegistry.lastKnownName("uuid-1"))
    }

    @Test
    fun `logs events and proxy views are served`() = testApplication {
        val client = apiClient()
        eventLog.record("service", "Lobby-1 started")

        val logs: LogsResponse = client.get("/api/v1/logs") { bearerAuth("secret") }.body()
        assertTrue(logs.lines.any { it.contains("boot line") })

        val events: List<org.helix.node.events.Event> = client.get("/api/v1/events") { bearerAuth("secret") }.body()
        assertEquals("Lobby-1 started", events.first().message)

        client.put("/api/v1/tasks/Lobby") {
            bearerAuth("secret"); contentType(ContentType.Application.Json); setBody(lobby)
        }
        client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }
        manager.handleHeartbeat(HeartbeatReport("Lobby-1", 4, 100))

        val proxyOff: ProxyView = client.get("/api/v1/proxy") { bearerAuth("secret") }.body()
        assertEquals(false, proxyOff.maintenance)
        assertEquals("Lobby-1", proxyOff.backends.single().id)

        client.post("/api/v1/proxy/maintenance") {
            bearerAuth("secret"); contentType(ContentType.Application.Json); setBody(MaintenanceRequest(true))
        }
        val proxyOn: ProxyView = client.get("/api/v1/proxy") { bearerAuth("secret") }.body()
        assertTrue(proxyOn.maintenance)
    }

    @Test
    fun `bridge values are filtered by the service's task addons`() = testApplication {
        val client = apiClient()
        dependencies.bridgeValues.publish("helix.chat", "chat.format", "{message}")
        dependencies.bridgeValues.publish("helix.tablist", "tablist.header", "hi")
        client.put("/api/v1/tasks/Lobby") {
            bearerAuth("secret"); contentType(ContentType.Application.Json)
            setBody(lobby.copy(disabledAddons = listOf("helix.chat")))
        }
        client.post("/api/v1/tasks/Lobby/services") { bearerAuth("secret") }

        val all: Map<String, String> = client.get("/api/v1/internal/bridge-values") { bearerAuth("secret") }.body()
        assertTrue(all.containsKey("chat.format") && all.containsKey("tablist.header"))

        val scoped: Map<String, String> =
            client.get("/api/v1/internal/bridge-values?serviceId=Lobby-1") { bearerAuth("secret") }.body()
        assertTrue(scoped.containsKey("tablist.header"))
        assertTrue(!scoped.containsKey("chat.format"))
    }

    @Test
    fun `ban snapshot proxies the bans addon's export, empty when not installed`() = testApplication {
        val client = apiClient()

        assertEquals("[]", client.get("/api/v1/internal/ban-snapshot") { bearerAuth("secret") }.bodyAsText())

        registry.register(ActionDescriptor("ban.export", "test export", "ban.export")) {
            ActionResult.ok("""[{"player":"griefer","reason":"spam","createdAtEpochMs":1,"expiresAtEpochMs":null}]""")
        }

        assertEquals(
            """[{"player":"griefer","reason":"spam","createdAtEpochMs":1,"expiresAtEpochMs":null}]""",
            client.get("/api/v1/internal/ban-snapshot") { bearerAuth("secret") }.bodyAsText(),
        )
    }

    @Test
    fun `translations are editable and synced to bridges over rest`() = testApplication {
        val client = apiClient()
        dependencies.messages.register(
            "velocity",
            org.helix.node.messages.MessageBundle(
                storage = org.helix.api.storage.InMemoryAddonStorage(),
                defaults = mapOf(
                    "en" to mapOf("screen.maintenance" to "Maintenance"),
                    "de" to mapOf("screen.maintenance" to "Wartung"),
                ),
                defaultLanguage = dependencies.languages::defaultLanguage,
                languageOf = dependencies.languages::languageOf,
            ),
        )
        dependencies.messages.register(
            "custom",
            org.helix.node.messages.MessageBundle(
                org.helix.api.storage.InMemoryAddonStorage(),
                emptyMap(),
            ),
        )

        val view: TranslationsView = client.get("/api/v1/translations") { bearerAuth("secret") }.body()
        assertEquals("en", view.defaultLanguage)
        assertTrue(view.languages.containsAll(listOf("en", "de")))
        assertTrue(view.entries.any { it.key == "helix.translations.velocity.screen.maintenance" })

        // edit a German value, create a custom key, add a language
        val edit = client.post("/api/v1/translations") {
            bearerAuth("secret"); contentType(ContentType.Application.Json)
            setBody(TranslationUpdate("helix.translations.velocity.screen.maintenance", "de", "Bald wieder da"))
        }
        assertEquals(HttpStatusCode.OK, edit.status)
        client.post("/api/v1/translations") {
            bearerAuth("secret"); contentType(ContentType.Application.Json)
            setBody(TranslationUpdate("helix.translations.custom.greeting", "en", "Hi there"))
        }
        client.post("/api/v1/translations/languages") {
            bearerAuth("secret"); contentType(ContentType.Application.Json)
            setBody(LanguageUpdate("fr"))
        }

        val snapshot: org.helix.api.i18n.TranslationsSnapshot =
            client.get("/api/v1/internal/translations") { bearerAuth("secret") }.body()
        assertEquals("Bald wieder da", snapshot.values["de"]!!["helix.translations.velocity.screen.maintenance"])
        assertEquals("Hi there", snapshot.values["en"]!!["helix.translations.custom.greeting"])
        assertTrue("fr" in snapshot.languages)

        // unknown owners are rejected, custom keys are deletable
        val unknown = client.post("/api/v1/translations") {
            bearerAuth("secret"); contentType(ContentType.Application.Json)
            setBody(TranslationUpdate("helix.translations.nope.key", "en", "x"))
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        val deleted = client.delete("/api/v1/translations/helix.translations.custom.greeting") { bearerAuth("secret") }
        assertEquals(HttpStatusCode.OK, deleted.status)

        // first-join locale is applied for new players
        val locale = client.post("/api/v1/internal/player-language") {
            bearerAuth("secret"); contentType(ContentType.Application.Json)
            setBody(org.helix.api.player.PlayerLocaleReport("Erik", "de_DE"))
        }
        assertEquals(HttpStatusCode.OK, locale.status)
        assertEquals("de", dependencies.languages.languageOf("erik"))
    }

    @Test
    fun `network pack routes serve the merged pack`() = testApplication {
        val client = apiClient()

        // without any addon pack every network pack route answers 404 / null
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/packs/network.zip").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/packs/network.sha1").status)
        val empty: NetworkPackInfo = client.get("/api/v1/internal/pack") { bearerAuth("secret") }.body()
        assertEquals(null, empty.sha1)

        val source = paths.root.resolve("fake-pack.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(source)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("assets/icon.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        networkPack.rebuild(listOf("helix.test" to source))

        // pack routes are public (clients download without a token)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/packs/network.zip").status)
        assertEquals(networkPack.sha1(), client.get("/api/v1/packs/network.sha1").body<String>())

        val info: NetworkPackInfo = client.get("/api/v1/internal/pack") { bearerAuth("secret") }.body()
        assertEquals(networkPack.sha1(), info.sha1)
        assertEquals("/api/v1/packs/network.zip", info.path)
    }

    @Test
    fun `dashboard is served without authentication`() = testApplication {
        val client = apiClient()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        // The React (shadcn) dashboard mounts into <div id="root">.
        assertTrue(response.body<String>().contains("id=\"root\""))
    }

    /** Logs a player in over the real REST flow and returns their session token. */
    private suspend fun HttpClient.loginAs(name: String): String {
        var code: String? = null
        registry.onInvocation { invocation, _ ->
            if (invocation.action == "player.message" && invocation.arguments.firstOrNull() == name) {
                code = Regex("""\d{6}""").find(invocation.arguments.getOrNull(1).orEmpty())?.value ?: code
            }
        }
        assertEquals(
            HttpStatusCode.OK,
            post("/api/v1/auth/request-code") {
                contentType(ContentType.Application.Json)
                setBody(org.helix.node.control.auth.LoginRequest(name))
            }.status,
        )
        val verify: org.helix.node.control.auth.SessionResponse = post("/api/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(org.helix.node.control.auth.VerifyRequest(name, requireNotNull(code)))
        }.body()
        return verify.token
    }

    @Test
    fun `post actions enforces the invoked action's declared permission`() = testApplication {
        val client = apiClient()
        registry.register(
            ActionDescriptor("test.gated", "gated action", "test.gated", permission = "test.gated.perm"),
        ) { ActionResult.ok("ran") }
        dependencies.playerRegistry.handle(
            org.helix.api.player.PlayerEvent("join", "Steve", "u", proxyServiceId = "Proxy-1"),
        )
        dependencies.nativePermissions.update("steve", listOf("helix.panel.login"))
        val token = client.loginAs("Steve")

        // no gated permission yet -> 403, not silently allowed just for being logged in
        val denied = client.post("/api/v1/actions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ActionInvocation("test.gated"))
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        // grant the declared permission -> the action now succeeds
        dependencies.nativePermissions.update("steve", listOf("helix.panel.login", "test.gated.perm"))
        val allowed: ActionResult = client.post("/api/v1/actions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ActionInvocation("test.gated"))
        }.body()
        assertTrue(allowed.success)
    }

    @Test
    fun `post actions requires admin for actions without a declared permission`() = testApplication {
        val client = apiClient()
        registry.register(ActionDescriptor("test.noperm", "ungated action", "test.noperm")) {
            ActionResult.ok("ran")
        }
        dependencies.playerRegistry.handle(
            org.helix.api.player.PlayerEvent("join", "Steve", "u", proxyServiceId = "Proxy-1"),
        )
        dependencies.nativePermissions.update("steve", listOf("helix.panel.login"))
        val token = client.loginAs("Steve")

        val denied = client.post("/api/v1/actions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ActionInvocation("test.noperm"))
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        // the static admin token still works (safe default: admin-only, not open to everyone)
        val allowed: ActionResult = client.post("/api/v1/actions") {
            bearerAuth("secret")
            contentType(ContentType.Application.Json)
            setBody(ActionInvocation("test.noperm"))
        }.body()
        assertTrue(allowed.success)
    }

    @Test
    fun `post actions attributes the real player as the invocation actor for a panel session`() = testApplication {
        val client = apiClient()
        var capturedActor: String? = null
        registry.onInvocation { invocation, _ ->
            if (invocation.action == "test.audited") capturedActor = invocation.actor
        }
        registry.register(ActionDescriptor("test.audited", "audited", "test.audited")) { ActionResult.ok("ran") }
        dependencies.playerRegistry.handle(
            org.helix.api.player.PlayerEvent("join", "Steve", "u", proxyServiceId = "Proxy-1"),
        )
        dependencies.nativePermissions.update("steve", listOf("helix.panel.login", "helix.admin"))
        val token = client.loginAs("Steve")

        client.post("/api/v1/actions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ActionInvocation("test.audited"))
        }

        assertEquals("Steve", capturedActor)
    }

    @Test
    fun `sensitive routes are rate limited per ip`() = testApplication {
        val client = apiClient()

        val responses = (1..15).map {
            client.post("/api/v1/auth/request-code") {
                contentType(ContentType.Application.Json)
                setBody(org.helix.node.control.auth.LoginRequest("nobody"))
            }
        }

        assertTrue(responses.any { it.status == HttpStatusCode.TooManyRequests })
    }

    @Test
    fun `baseline security headers are present on every response`() = testApplication {
        val client = apiClient()

        val response = client.get("/api/v1/tasks") { bearerAuth("secret") }

        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("frame-ancestors 'none'", response.headers["Content-Security-Policy"])
    }
}
