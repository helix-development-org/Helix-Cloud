package org.helix.node.control

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
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
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.player.PlayerEvent
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.PermissionDecision
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.actions.PlayerCommandService
import org.helix.node.addons.AddonManager
import org.helix.node.config.NodeConfig
import org.helix.node.display.BridgeValueStore
import org.helix.node.display.DisplayResolverRegistry
import org.helix.node.events.EventLog
import org.helix.node.gates.JoinGateRegistry
import org.helix.node.gates.PermissionResolverRegistry
import org.helix.node.logging.LogBuffer
import org.helix.node.players.PlayerRegistry
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.proxy.ProxyCommandQueue
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.services.ServiceManager
import org.helix.node.tasks.TaskStore
import org.helix.node.actions.ActionRegistry
import org.slf4j.LoggerFactory

/**
 * Dependencies of the control API routes.
 *
 * @property token bearer token required on every `/api` route.
 * @property registry action entry point.
 * @property taskStore configured tasks.
 * @property manager service lifecycle owner.
 * @property routing proxy routing state.
 * @property overviewService aggregated platform counters.
 * @property addonManager installed addons.
 * @property joinGates aggregated join gates of all addons.
 * @property commandQueue pending commands for proxy bridges.
 * @property permissionResolvers aggregated permission resolvers of all addons.
 */
data class ControlDependencies(
    val token: String,
    val registry: ActionRegistry,
    val taskStore: TaskStore,
    val manager: ServiceManager,
    val routing: ProxyRoutingService,
    val overviewService: PlatformOverviewService,
    val addonManager: AddonManager,
    val joinGates: JoinGateRegistry = JoinGateRegistry(),
    val commandQueue: ProxyCommandQueue = ProxyCommandQueue(),
    val permissionResolvers: PermissionResolverRegistry = PermissionResolverRegistry(),
    val playerRegistry: PlayerRegistry = PlayerRegistry(),
    val displayResolvers: DisplayResolverRegistry = DisplayResolverRegistry(),
    val bridgeValues: BridgeValueStore = BridgeValueStore(),
    val logBuffer: LogBuffer = LogBuffer(),
    val eventLog: EventLog = EventLog(),
) {
    /** Player command execution shared by the internal routes. */
    val playerCommands: PlayerCommandService = PlayerCommandService(registry, permissionResolvers)
}

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
                if (credential.token == dependencies.token) UserIdPrincipal("helix") else null
            }
        }
    }
    routing {
        staticResources("/", "dashboard") {
            default("index.html")
        }
        authenticate("helix") {
            route("/api/v1") {
                platformRoutes(dependencies)
                taskRoutes(dependencies)
                serviceRoutes(dependencies)
                proxyRoutes(dependencies)
                observabilityRoutes(dependencies)
                actionRoutes(dependencies)
                addonRoutes(dependencies)
                internalRoutes(dependencies)
            }
        }
    }
}

private fun io.ktor.server.routing.Route.platformRoutes(dependencies: ControlDependencies) {
    get("/platform/overview") {
        call.respond(dependencies.overviewService.overview())
    }
}

private fun io.ktor.server.routing.Route.observabilityRoutes(dependencies: ControlDependencies) {
    get("/logs") {
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 300
        call.respond(LogsResponse(dependencies.logBuffer.tail(tail)))
    }
    get("/events") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
        call.respond(dependencies.eventLog.recent(limit))
    }
}

private fun io.ktor.server.routing.Route.proxyRoutes(dependencies: ControlDependencies) {
    get("/proxy") {
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
        val request = call.receive<MaintenanceRequest>()
        dependencies.routing.maintenance = request.enabled
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
        call.respond(dependencies.taskStore.all())
    }
    get("/tasks/{name}") {
        val task = dependencies.taskStore.find(call.parameters["name"].orEmpty())
        if (task == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown task"))
        } else {
            call.respond(task)
        }
    }
    put("/tasks/{name}") {
        val task = call.receive<TaskDefinition>()
        require(task.name == call.parameters["name"]) { "task name must match the url" }
        dependencies.taskStore.save(task)
        call.respond(task)
    }
    delete("/tasks/{name}") {
        val name = call.parameters["name"].orEmpty()
        require(dependencies.manager.activeCount(name) == 0) { "task $name still has active services" }
        if (dependencies.taskStore.delete(name)) {
            call.respond(MessageResponse("deleted task $name"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown task"))
        }
    }
    post("/tasks/{name}/services") {
        val info = dependencies.manager.startService(call.parameters["name"].orEmpty())
        call.respond(HttpStatusCode.Created, info)
    }
}

private fun io.ktor.server.routing.Route.serviceRoutes(dependencies: ControlDependencies) {
    get("/services") {
        call.respond(dependencies.manager.services())
    }
    get("/services/{id}") {
        val info = dependencies.manager.find(call.parameters["id"].orEmpty())?.toInfo()
        if (info == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown service"))
        } else {
            call.respond(info)
        }
    }
    post("/services/{id}/stop") {
        val id = call.parameters["id"].orEmpty()
        if (dependencies.manager.stopService(id)) {
            call.respond(MessageResponse("stopping $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("service not running: $id"))
        }
    }
    post("/services/{id}/kill") {
        val id = call.parameters["id"].orEmpty()
        if (dependencies.manager.killService(id)) {
            call.respond(MessageResponse("killed $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("service not running: $id"))
        }
    }
    get("/services/{id}/logs") {
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 50
        call.respond(LogsResponse(dependencies.manager.logs(call.parameters["id"].orEmpty(), tail)))
    }
}

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
        call.respond(dependencies.addonManager.addons())
    }
    post("/addons/{id}/enable") {
        val id = call.parameters["id"].orEmpty()
        if (dependencies.addonManager.enable(id)) {
            call.respond(MessageResponse("enabled $id"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown addon: $id"))
        }
    }
    post("/addons/{id}/disable") {
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
        val report = call.receive<HeartbeatReport>()
        if (dependencies.manager.handleHeartbeat(report)) {
            call.respond(MessageResponse("ok"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown service: ${report.serviceId}"))
        }
    }
    get("/internal/routing") {
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        call.respond(dependencies.routing.snapshot(proxyServiceId))
    }
    post("/internal/join-check") {
        val request = call.receive<JoinRequest>()
        call.respond(dependencies.joinGates.evaluate(request))
    }
    get("/internal/commands") {
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        call.respond(dependencies.commandQueue.drain(proxyServiceId))
    }
    post("/internal/permission-check") {
        val request = call.receive<PermissionCheckRequest>()
        call.respond(PermissionDecision(dependencies.permissionResolvers.evaluate(request)))
    }
    post("/internal/player-event") {
        val event = call.receive<PlayerEvent>()
        if (dependencies.playerRegistry.handle(event)) {
            call.respond(MessageResponse("ok"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown event type: ${event.type}"))
        }
    }
    get("/internal/players") {
        call.respond(dependencies.playerRegistry.online())
    }
    get("/internal/player-commands") {
        call.respond(dependencies.playerCommands.commands())
    }
    post("/internal/player-command") {
        val request = call.receive<PlayerCommandRequest>()
        call.respond(dependencies.playerCommands.execute(request))
    }
    post("/internal/display") {
        val request = call.receive<JoinRequest>()
        call.respond(dependencies.displayResolvers.resolve(request.name))
    }
    get("/internal/bridge-values") {
        call.respond(dependencies.bridgeValues.all())
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
     * Starts the server without blocking.
     */
    fun start() {
        engine = embeddedServer(Netty, port = settings.port, host = settings.host) {
            controlModule(dependencies)
        }.start(wait = false)
        logger.info("Control API listening on http://{}:{}", settings.host, settings.port)
    }

    /**
     * Stops the server gracefully.
     */
    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        engine = null
    }
}
