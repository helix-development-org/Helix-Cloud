package org.helix.node.control

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import java.io.FileInputStream
import java.security.KeyStore
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionSource
import org.helix.api.action.PlayerCommandRequest
import org.helix.node.control.auth.LoginRequest
import org.helix.node.control.auth.PanelAuthService
import org.helix.node.control.auth.PanelPrincipal
import org.helix.node.control.auth.VerifyRequest
import org.helix.node.scheduler.ScheduledJob
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.player.PlayerEvent
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.PermissionDecision
import org.helix.api.proxy.ProxyPoll
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.config.NodeConfig
import org.slf4j.LoggerFactory

/** Maximum time a proxy long-poll is held open before returning empty. */
private const val POLL_TIMEOUT_MS = 25_000L

/** Longest single wait between re-checks inside a long-poll. */
private const val POLL_RECHECK_MS = 1_000L

/**
 * Installs the complete control API and the dashboard.
 *
 * All `/api` routes require `Authorization: Bearer <token>`; the dashboard
 * assets at `/` are public and authenticate their API calls themselves.
 *
 * @param dependencies control API dependencies.
 */
fun Application.controlModule(dependencies: ControlDependencies) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "error"))
        }
    }
    install(Authentication) {
        bearer("helix") {
            authenticate { credential ->
                dependencies.panelAuth.authenticate(credential.token)
            }
        }
    }
    intercept(ApplicationCallPipeline.Monitoring) {
        val startNanos = System.nanoTime()
        proceed()
        val path = call.request.path()
        if (path.startsWith("/api/") && !path.startsWith("/api/v1/internal/poll")) {
            val status = call.response.status()?.value ?: 0
            dependencies.apiMetrics.record((System.nanoTime() - startNanos) / 1_000_000.0, status)
            val actor = call.principal<PanelPrincipal>()?.name ?: "anonymous"
            val outcome = when {
                status == 401 || status == 403 -> "denied"
                status in 200..399 -> "ok"
                else -> "error"
            }
            dependencies.audit.record(
                "http",
                actor,
                "${call.request.httpMethod.value} $path → $status",
                outcome,
            )
        }
    }
    routing {
        staticResources("/", "dashboard") {
            default("index.html")
        }
        route("/api/v1") {
            publicAuthRoutes(dependencies)
        }
        authenticate("helix") {
            route("/api/v1") {
                sessionRoutes(dependencies)
                platformRoutes(dependencies)
                taskRoutes(dependencies)
                serviceRoutes(dependencies)
                playerRoutes(dependencies)
                proxyRoutes(dependencies)
                observabilityRoutes(dependencies)
                panelRoutes(dependencies)
                messageRoutes(dependencies)
                scheduleRoutes(dependencies)
                actionRoutes(dependencies)
                addonRoutes(dependencies)
                internalRoutes(dependencies)
            }
        }
    }
}

/**
 * Ensures the caller holds a permission node, else answers `403`.
 *
 * @param dependencies control API dependencies.
 * @param node the required permission node.
 * @return `true` if authorized; `false` after a `403` was written.
 */
private suspend fun RoutingContext.authorize(dependencies: ControlDependencies, node: String): Boolean {
    val principal = call.principal<PanelPrincipal>()
    if (principal != null && dependencies.panelAuth.grants(principal, node)) {
        return true
    }
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("missing permission: $node"))
    return false
}

/**
 * Ensures the caller authenticated with the static admin token, else `403`.
 *
 * Internal machine routes (bridges, wrappers) require the admin token; a
 * player session must never reach them.
 *
 * @param dependencies control API dependencies.
 * @return `true` if the caller is the admin token; `false` after a `403`.
 */
private suspend fun RoutingContext.requireAdmin(dependencies: ControlDependencies): Boolean {
    if (call.principal<PanelPrincipal>()?.admin == true) {
        return true
    }
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("admin token required"))
    return false
}

/**
 * The permission nodes bridges evaluate natively on join.
 *
 * These are the nodes the node actually checks — the login permission, every
 * dashboard view, each addon panel and the maintenance bypass — so the
 * Minecraft-native default can answer them without a permission addon.
 *
 * @param dependencies control API dependencies.
 * @return the distinct permission nodes to evaluate.
 */
private fun knownPermissionNodes(dependencies: ControlDependencies): List<String> = buildList {
    add(dependencies.loginPermission)
    addAll(PanelAuthService.VIEW_NODES.values)
    dependencies.dashboardPanels.list().forEach { add(PanelAuthService.panelNode(it.id)) }
    add("helix.maintenance.bypass")
    // permissions gating in-game commands (/helix, /bans, /permissions, …)
    dependencies.registry.descriptors()
        .filter { it.playerCommand }
        .mapNotNull { it.permission }
        .forEach { add(it) }
}.distinct()

private fun io.ktor.server.routing.Route.publicAuthRoutes(dependencies: ControlDependencies) {
    post("/auth/request-code") {
        val request = call.receive<LoginRequest>()
        call.respond(dependencies.panelAuth.requestCode(request.name))
    }
    post("/auth/verify") {
        val request = call.receive<VerifyRequest>()
        call.respond(dependencies.panelAuth.verify(request.name, request.code))
    }
}

private fun io.ktor.server.routing.Route.sessionRoutes(dependencies: ControlDependencies) {
    get("/auth/me") {
        val principal = call.principal<PanelPrincipal>()!!
        call.respond(dependencies.panelAuth.identity(principal))
    }
    post("/auth/logout") {
        val header = call.request.headers[HttpHeaders.Authorization].orEmpty()
        dependencies.panelAuth.logout(header.removePrefix("Bearer ").trim())
        call.respond(MessageResponse("signed out"))
    }
}

private fun io.ktor.server.routing.Route.platformRoutes(dependencies: ControlDependencies) {
    get("/platform/overview") {
        if (!authorize(dependencies, "helix.panel.overview")) return@get
        call.respond(dependencies.overviewService.overview())
    }
    get("/metrics") {
        if (!authorize(dependencies, "helix.panel.overview")) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 240
        call.respond(dependencies.metrics.recent(limit))
    }
    get("/api-stats") {
        if (!authorize(dependencies, "helix.panel.overview")) return@get
        call.respond(dependencies.apiMetrics.snapshot())
    }
}

private fun io.ktor.server.routing.Route.observabilityRoutes(dependencies: ControlDependencies) {
    get("/logs") {
        if (!authorize(dependencies, "helix.panel.logs")) return@get
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 300
        call.respond(LogsResponse(dependencies.logBuffer.tail(tail)))
    }
    get("/events") {
        if (!authorize(dependencies, "helix.panel.events")) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
        call.respond(dependencies.eventLog.recent(limit))
    }
    get("/audit") {
        if (!authorize(dependencies, "helix.panel.audit")) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 300
        val category = call.request.queryParameters["category"]
        call.respond(dependencies.audit.recent(limit, category))
    }
}

private fun io.ktor.server.routing.Route.panelRoutes(dependencies: ControlDependencies) {
    get("/panels") {
        val principal = call.principal<PanelPrincipal>()!!
        val visible = dependencies.dashboardPanels.list().filter {
            dependencies.panelAuth.grants(principal, PanelAuthService.panelNode(it.id))
        }
        call.respond(visible)
    }
    get("/panels/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (!authorize(dependencies, PanelAuthService.panelNode(id))) return@get
        val panel = dependencies.dashboardPanels.find(id)
        if (panel == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown panel"))
        } else {
            call.respond(panel)
        }
    }
}

private fun io.ktor.server.routing.Route.messageRoutes(dependencies: ControlDependencies) {
    get("/messages") {
        if (!authorize(dependencies, "helix.panel.messages")) return@get
        call.respond(dependencies.messages.all())
    }
    post("/messages") {
        if (!authorize(dependencies, "helix.panel.messages")) return@post
        val update = call.receive<MessageUpdate>()
        val ok = if (update.reset) {
            dependencies.messages.reset(update.addonId, update.key)
        } else {
            dependencies.messages.set(update.addonId, update.key, update.value)
        }
        if (ok) {
            dependencies.onMessagesChanged(update.addonId)
            call.respond(MessageResponse("updated ${update.addonId}.${update.key}"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown message ${update.addonId}.${update.key}"))
        }
    }
}

private fun io.ktor.server.routing.Route.proxyRoutes(dependencies: ControlDependencies) {
    get("/proxy") {
        if (!authorize(dependencies, "helix.panel.proxy")) return@get
        val services = dependencies.manager.managedServices()
        val proxies = services.filter { it.task.environment.proxy }.map {
            ProxySummary(
                id = it.id,
                state = it.state.name,
                executor = it.task.executor.name,
                port = it.port,
                onlinePlayers = it.onlinePlayers,
                maxPlayers = it.maxPlayers,
            )
        }
        val referenceProxy = proxies.firstOrNull()?.id ?: ""
        val routed = dependencies.routing.snapshot(referenceProxy).backends.associateBy { it.serviceId }
        val backends = services
            .filter { !it.task.environment.proxy && it.state == ServiceState.RUNNING }
            .map { backend ->
                val route = routed[backend.id]
                ProxyBackendView(
                    id = backend.id,
                    task = backend.task.name,
                    state = backend.state.name,
                    host = route?.host ?: "127.0.0.1",
                    port = route?.port ?: backend.port,
                    onlinePlayers = backend.onlinePlayers,
                    fallbackEligible = backend.task.fallbackEligible,
                )
            }
        call.respond(ProxyView(dependencies.routing.maintenance, proxies, backends))
    }
    post("/proxy/maintenance") {
        if (!authorize(dependencies, "helix.panel.proxy")) return@post
        val request = call.receive<MaintenanceRequest>()
        dependencies.routing.maintenance = request.enabled
        dependencies.proxyEvents.bumpRouting()
        dependencies.eventLog.record(
            "proxy",
            if (request.enabled) "Maintenance enabled" else "Maintenance disabled",
            if (request.enabled) "warn" else "info",
        )
        call.respond(MessageResponse("maintenance ${if (request.enabled) "enabled" else "disabled"}"))
    }
}

private fun io.ktor.server.routing.Route.taskRoutes(dependencies: ControlDependencies) {
    get("/tasks") {
        if (!authorize(dependencies, "helix.panel.tasks")) return@get
        call.respond(dependencies.taskStore.all())
    }
    get("/tasks/{name}") {
        if (!authorize(dependencies, "helix.panel.tasks")) return@get
        val task = dependencies.taskStore.find(call.parameters["name"].orEmpty())
        if (task == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown task"))
        } else {
            call.respond(task)
        }
    }
    put("/tasks/{name}") {
        if (!authorize(dependencies, "helix.panel.tasks")) return@put
        val task = call.receive<TaskDefinition>()
        require(task.name == call.parameters["name"]) { "task name must match the url" }
        dependencies.taskStore.save(task)
        call.respond(task)
    }
    delete("/tasks/{name}") {
        if (!authorize(dependencies, "helix.panel.tasks")) return@delete
        val name = call.parameters["name"].orEmpty()
        require(dependencies.manager.activeCount(name) == 0) { "task $name still has active services" }
        if (dependencies.taskStore.delete(name)) {
            call.respond(MessageResponse("deleted task $name"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown task"))
        }
    }
    post("/tasks/{name}/services") {
        if (!authorize(dependencies, "helix.panel.tasks")) return@post
        val info = dependencies.manager.startService(call.parameters["name"].orEmpty())
        call.respond(HttpStatusCode.Created, info)
    }
}

private fun io.ktor.server.routing.Route.serviceRoutes(dependencies: ControlDependencies) {
    get("/services") {
        if (!authorize(dependencies, "helix.panel.services")) return@get
        call.respond(dependencies.manager.services())
    }
    get("/services/{id}") {
        if (!authorize(dependencies, "helix.panel.services")) return@get
        val info = dependencies.manager.find(call.parameters["id"].orEmpty())?.toInfo()
        if (info == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown service"))
        } else {
            call.respond(info)
        }
    }
    post("/services/{id}/stop") {
        if (!authorize(dependencies, "helix.panel.services")) return@post
        val id = call.parameters["id"].orEmpty()
        if (dependencies.manager.stopService(id)) {
            call.respond(MessageResponse("stopping $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("service not running: $id"))
        }
    }
    post("/services/{id}/kill") {
        if (!authorize(dependencies, "helix.panel.services")) return@post
        val id = call.parameters["id"].orEmpty()
        if (dependencies.manager.killService(id)) {
            call.respond(MessageResponse("killed $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("service not running: $id"))
        }
    }
    get("/services/{id}/logs") {
        if (!authorize(dependencies, "helix.panel.services")) return@get
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 50
        call.respond(LogsResponse(dependencies.manager.logs(call.parameters["id"].orEmpty(), tail)))
    }
    post("/services/{id}/command") {
        if (!authorize(dependencies, "helix.panel.services")) return@post
        val id = call.parameters["id"].orEmpty()
        val command = call.receive<ServiceCommandRequest>().command
        require(command.isNotBlank()) { "command must not be empty" }
        if (dependencies.manager.sendCommand(id, command)) {
            dependencies.audit.record(
                "console",
                call.principal<PanelPrincipal>()?.name ?: "anonymous",
                "$id » $command",
            )
            call.respond(MessageResponse("sent to $id"))
        } else {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("service not running: $id"),
            )
        }
    }
}

private fun io.ktor.server.routing.Route.scheduleRoutes(dependencies: ControlDependencies) {
    get("/schedules") {
        if (!authorize(dependencies, "helix.panel.schedules")) return@get
        call.respond(dependencies.jobScheduler.all())
    }
    post("/schedules") {
        if (!authorize(dependencies, "helix.panel.schedules")) return@post
        val job = call.receive<ScheduledJob>()
        require(job.id.isNotBlank()) { "job id must not be empty" }
        require(job.action.isNotBlank()) { "job action must not be empty" }
        require(job.everyMinutes > 0 || job.dailyAt != null) { "set everyMinutes or dailyAt" }
        dependencies.jobScheduler.save(job)
        call.respond(job)
    }
    delete("/schedules/{id}") {
        if (!authorize(dependencies, "helix.panel.schedules")) return@delete
        val id = call.parameters["id"].orEmpty()
        if (dependencies.jobScheduler.delete(id)) {
            call.respond(MessageResponse("deleted $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown job: $id"))
        }
    }
    post("/schedules/{id}/run") {
        if (!authorize(dependencies, "helix.panel.schedules")) return@post
        val id = call.parameters["id"].orEmpty()
        if (dependencies.jobScheduler.runNow(id)) {
            call.respond(MessageResponse("ran $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown job: $id"))
        }
    }
}

private fun io.ktor.server.routing.Route.playerRoutes(dependencies: ControlDependencies) {
    get("/players") {
        if (!authorize(dependencies, "helix.panel.players")) return@get
        call.respond(dependencies.playerRegistry.online())
    }
    post("/players/{name}/message") {
        if (!authorize(dependencies, "helix.panel.players")) return@post
        val name = call.parameters["name"].orEmpty()
        val text = call.receive<PlayerActionRequest>().value
        require(text.isNotBlank()) { "message must not be empty" }
        call.respond(dependencies.registry.invoke(playerAction("player.message", name, text)))
    }
    post("/players/{name}/kick") {
        if (!authorize(dependencies, "helix.panel.players")) return@post
        val name = call.parameters["name"].orEmpty()
        val reason = call.receive<PlayerActionRequest>().value
        call.respond(dependencies.registry.invoke(playerAction("player.kick", name, reason)))
    }
    post("/players/{name}/ban") {
        if (!authorize(dependencies, "helix.panel.players")) return@post
        val name = call.parameters["name"].orEmpty()
        val request = call.receive<PlayerActionRequest>()
        val arguments = buildList {
            add(name)
            request.duration?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (request.value.isNotBlank()) add(request.value)
        }
        call.respond(dependencies.registry.invoke(ActionInvocation("ban.set", arguments, ActionSource.REST)))
    }
}

private fun playerAction(action: String, player: String, value: String): ActionInvocation =
    ActionInvocation(
        action = action,
        arguments = if (value.isBlank()) listOf(player) else listOf(player, value),
        source = ActionSource.REST,
    )

private fun io.ktor.server.routing.Route.actionRoutes(dependencies: ControlDependencies) {
    get("/actions") {
        call.respond(dependencies.registry.descriptors())
    }
    post("/actions") {
        val invocation = call.receive<ActionInvocation>()
        call.respond(dependencies.registry.invoke(invocation.copy(source = ActionSource.REST)))
    }
}

private fun io.ktor.server.routing.Route.addonRoutes(dependencies: ControlDependencies) {
    get("/addons") {
        if (!authorize(dependencies, "helix.panel.addons")) return@get
        call.respond(dependencies.addonManager.addons())
    }
    post("/addons/{id}/enable") {
        if (!authorize(dependencies, "helix.panel.addons")) return@post
        val id = call.parameters["id"].orEmpty()
        if (dependencies.addonManager.enable(id)) {
            call.respond(MessageResponse("enabled $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown addon: $id"))
        }
    }
    post("/addons/{id}/disable") {
        if (!authorize(dependencies, "helix.panel.addons")) return@post
        val id = call.parameters["id"].orEmpty()
        if (dependencies.addonManager.disable(id)) {
            call.respond(MessageResponse("disabled $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown addon: $id"))
        }
    }
}

private fun io.ktor.server.routing.Route.internalRoutes(dependencies: ControlDependencies) {
    post("/internal/heartbeat") {
        if (!requireAdmin(dependencies)) return@post
        val report = call.receive<HeartbeatReport>()
        if (dependencies.manager.handleHeartbeat(report)) {
            call.respond(MessageResponse("ok"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown service: ${report.serviceId}"))
        }
    }
    get("/internal/routing") {
        if (!requireAdmin(dependencies)) return@get
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        call.respond(
            dependencies.routing.snapshot(proxyServiceId).copy(
                networkName = dependencies.networkName(),
                maintenanceScreen = dependencies.proxyScreens.raw("maintenance"),
                serverFullScreen = dependencies.proxyScreens.raw("server_full"),
            ),
        )
    }
    post("/internal/join-check") {
        if (!requireAdmin(dependencies)) return@post
        val request = call.receive<JoinRequest>()
        call.respond(dependencies.joinGates.evaluate(request))
    }
    get("/internal/commands") {
        if (!requireAdmin(dependencies)) return@get
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        call.respond(dependencies.commandQueue.drain(proxyServiceId))
    }
    get("/internal/poll") {
        if (!requireAdmin(dependencies)) return@get
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        val seenRouting = call.request.queryParameters["routingVersion"]?.toIntOrNull() ?: -1
        val seenCatalog = call.request.queryParameters["commandCatalogVersion"]?.toIntOrNull() ?: -1
        val hub = dependencies.proxyEvents
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (true) {
            val commands = dependencies.commandQueue.drain(proxyServiceId)
            val routing = hub.routingVersion.get()
            val catalog = hub.commandCatalogVersion.get()
            val changed = commands.isNotEmpty() || routing != seenRouting || catalog != seenCatalog
            if (changed || System.currentTimeMillis() >= deadline) {
                call.respond(ProxyPoll(commands, routing, catalog))
                break
            }
            hub.await((deadline - System.currentTimeMillis()).coerceIn(1, POLL_RECHECK_MS))
        }
    }
    post("/internal/permission-check") {
        if (!requireAdmin(dependencies)) return@post
        val request = call.receive<PermissionCheckRequest>()
        call.respond(PermissionDecision(dependencies.permissionService.check(request)))
    }
    get("/internal/permission-nodes") {
        if (!requireAdmin(dependencies)) return@get
        call.respond(knownPermissionNodes(dependencies))
    }
    post("/internal/player-event") {
        if (!requireAdmin(dependencies)) return@post
        val event = call.receive<PlayerEvent>()
        if (dependencies.playerRegistry.handle(event)) {
            when (event.type) {
                "join" -> dependencies.nativePermissions.update(event.name, event.permissions)
                "leave" -> dependencies.nativePermissions.clear(event.name)
            }
            call.respond(MessageResponse("ok"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown event type: ${event.type}"))
        }
    }
    get("/internal/players") {
        if (!requireAdmin(dependencies)) return@get
        call.respond(dependencies.playerRegistry.online())
    }
    get("/internal/player-commands") {
        if (!requireAdmin(dependencies)) return@get
        call.respond(dependencies.playerCommands.commands())
    }
    post("/internal/player-command") {
        if (!requireAdmin(dependencies)) return@post
        val request = call.receive<PlayerCommandRequest>()
        call.respond(dependencies.playerCommands.execute(request))
    }
    post("/internal/display") {
        if (!requireAdmin(dependencies)) return@post
        val request = call.receive<JoinRequest>()
        call.respond(dependencies.displayResolvers.resolve(request.name))
    }
    get("/internal/bridge-values") {
        if (!requireAdmin(dependencies)) return@get
        val serviceId = call.request.queryParameters["serviceId"]
        val task = serviceId?.let { dependencies.manager.find(it)?.task }
        val values = if (task == null) {
            dependencies.bridgeValues.all()
        } else {
            dependencies.bridgeValues.all { owner -> task.isAddonActive(owner) }
        }
        call.respond(values)
    }
}

/**
 * Embedded Netty server hosting the control API and dashboard.
 *
 * @property settings bind host and port.
 * @property dependencies control API dependencies.
 */
class ControlServer(
    private val settings: NodeConfig.ControlSettings,
    private val dependencies: ControlDependencies,
) {
    private val logger = LoggerFactory.getLogger(ControlServer::class.java)
    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Starts the server without blocking, over HTTPS when a keystore is
     * configured, otherwise HTTP.
     */
    fun start() {
        engine = if (settings.isTls()) startTls() else startPlain()
        logger.info(
            "Control API listening on {}://{}:{}",
            if (settings.isTls()) "https" else "http",
            settings.host,
            settings.port,
        )
    }

    private fun startPlain(): EmbeddedServer<*, *> =
        embeddedServer(Netty, port = settings.port, host = settings.host) {
            controlModule(dependencies)
        }.start(wait = false)

    private fun startTls(): EmbeddedServer<*, *> {
        val password = settings.tlsKeystorePassword.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(settings.tlsKeystore).use { load(it, password) }
        }
        return embeddedServer(
            Netty,
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = settings.tlsKeyAlias,
                    keyStorePassword = { password },
                    privateKeyPassword = { password },
                ) {
                    port = settings.port
                    host = settings.host
                }
            },
            module = { controlModule(dependencies) },
        ).start(wait = false)
    }

    /**
     * Stops the server gracefully.
     */
    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        engine = null
    }
}
