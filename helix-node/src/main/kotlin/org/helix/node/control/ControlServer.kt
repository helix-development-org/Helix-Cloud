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
import io.ktor.server.plugins.origin
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
import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
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
import org.helix.api.display.DisplayBulkRequest
import org.helix.node.control.auth.LoginRequest
import org.helix.node.control.auth.PanelAuthService
import org.helix.node.control.auth.PanelPrincipal
import org.helix.node.control.auth.VerifyRequest
import org.helix.node.scheduler.ScheduledJob
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.i18n.TranslationsSnapshot
import org.helix.api.player.PlayerEvent
import org.helix.api.player.PlayerLocaleReport
import org.helix.api.player.PlayerPermissionsReport
import org.helix.api.player.PlayerRosterReport
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.PermissionDecision
import org.helix.api.proxy.PlayerPermissionsSnapshot
import org.helix.api.proxy.ProxyPoll
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import org.helix.node.config.NodeConfig
import org.slf4j.LoggerFactory

/** Logger for the top-level route builders (outside the [ControlServer] class). */
private val logger = LoggerFactory.getLogger("org.helix.node.control.ControlRoutes")

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
 * @param isTls whether this instance is served over HTTPS, gating whether
 *  `Strict-Transport-Security` is sent.
 */
fun Application.controlModule(dependencies: ControlDependencies, isTls: Boolean = false) {
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
                val scopedServiceId = dependencies.serviceTokens.serviceIdFor(credential.token)
                if (scopedServiceId != null) {
                    PanelPrincipal(name = "service:$scopedServiceId", admin = false, serviceId = scopedServiceId)
                } else {
                    dependencies.panelAuth.authenticate(credential.token)
                }
            }
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        // The SPA entry must revalidate after node updates (it references the
        // content-hashed bundle); the hashed assets themselves never change.
        val path = call.request.path()
        when {
            path == "/" || path == "/index.html" ->
                call.response.header(HttpHeaders.CacheControl, "no-cache")
            path.startsWith("/assets/") ->
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        }
        // Baseline hardening: the dashboard is never meant to be framed by another
        // origin (defeats the addon-panel postMessage confinement otherwise), and
        // HSTS only makes sense once we know we're actually serving over TLS.
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Content-Security-Policy", "frame-ancestors 'none'")
        if (isTls) {
            call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
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
            packRoutes(dependencies)
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
                translationRoutes(dependencies)
                scheduleRoutes(dependencies)
                backupRoutes(dependencies)
                fileRoutes(dependencies)
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
 * Internal machine routes (bridges, wrappers) require full admin: either the
 * static token, or a signed-in player whose account holds `helix.admin` — an
 * ordinary panel session never satisfies this.
 *
 * @param dependencies control API dependencies.
 * @return `true` for the admin token or a `helix.admin` session; `false`
 *  after a `403`.
 */
private suspend fun RoutingContext.requireAdmin(dependencies: ControlDependencies): Boolean {
    if (call.principal<PanelPrincipal>()?.admin == true) {
        return true
    }
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("admin token required"))
    return false
}

/**
 * Authorizes an `/internal/` bridge route: the static admin token, or a
 * per-service token — but ONLY when the route's own service id (if it names
 * one) matches the token's scope. This is what lets a Paper/Velocity process
 * carry a token minted just for it instead of the shared admin credential,
 * without letting it act for a different service or escalate to a
 * non-internal admin route (those go through [requireAdmin] or [authorize],
 * neither of which a per-service principal — `admin == false` — ever passes).
 *
 * @param dependencies control API dependencies.
 * @param serviceId the service id this specific call pertains to, or `null`
 *  for routes that carry none (network-wide bridge info, safe for any
 *  authenticated bridge to read).
 * @return `true` if authorized; `false` after a `403` was written.
 */
private suspend fun RoutingContext.requireBridge(dependencies: ControlDependencies, serviceId: String? = null): Boolean {
    val principal = call.principal<PanelPrincipal>()
    val authorized = when {
        principal == null -> false
        principal.admin -> true
        principal.serviceId == null -> false
        serviceId == null -> true
        else -> principal.serviceId == serviceId
    }
    if (!authorized) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("admin token or matching service token required"))
    }
    return authorized
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
internal fun knownPermissionNodes(dependencies: ControlDependencies): List<String> = buildList {
    add(dependencies.loginPermission)
    addAll(PanelAuthService.VIEW_NODES.values)
    dependencies.dashboardPanels.list().forEach { add(PanelAuthService.panelNode(it.id)) }
    add("helix.maintenance.bypass")
    // permissions gating in-game commands (/helix, /bans, /permissions, …)
    dependencies.registry.descriptors()
        .filter { it.playerCommand }
        .mapNotNull { it.permission }
        .forEach { add(it) }
    dependencies.addonManager.addons().forEach { addAll(it.manifest.permissions) }
    addAll(permissionCatalogNodes(dependencies))
}.distinct()

/**
 * Nodes contributed by the permissions addon's full catalog (core, addons
 * and backend plugin.yml scans), reusing its `perm.catalog` action instead
 * of duplicating the scan in the node core. Empty when the addon is not
 * installed.
 *
 * @param dependencies control API dependencies.
 * @return the catalog's permission nodes, or empty on any failure.
 */
private fun permissionCatalogNodes(dependencies: ControlDependencies): List<String> {
    val result = dependencies.registry.invoke(
        ActionInvocation(action = "perm.catalog", arguments = emptyList(), source = ActionSource.SYSTEM),
    )
    if (!result.success) {
        return emptyList()
    }
    return runCatching {
        Json.decodeFromString<List<PermissionCatalogEntry>>(result.lines.firstOrNull() ?: "[]").map { it.node }
    }.getOrDefault(emptyList())
}

/**
 * Structural mirror of the permissions addon's `CatalogEntry` — the node
 * core deliberately does not depend on addon modules, so the shared JSON
 * shape (`node`, `source`) is decoded independently here.
 *
 * @property node the permission node.
 * @property source where the node was discovered.
 */
@kotlinx.serialization.Serializable
private data class PermissionCatalogEntry(val node: String, val source: String)

/** Lines fetched per service-log poll while streaming. */
private const val STREAM_TAIL = 400

/** Milliseconds between stream polls. */
private const val STREAM_POLL_MS = 300L

/** Stream polls between keep-alive comments (~15 s). */
private const val STREAM_KEEPALIVE_POLLS = 50

/**
 * Responds with a server-sent-event stream and runs the given block with an
 * emitter that writes one SSE event per log line.
 *
 * @param block receives the raw event emitter.
 */
private suspend fun RoutingContext.streamSse(block: suspend (suspend (String) -> Unit) -> Unit) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        val emit: suspend (String) -> Unit = { raw ->
            write(raw)
            flush()
        }
        runCatching { block(emit) }
    }
}

/**
 * Emits log lines as SSE `data:` events.
 *
 * @param emit the raw event emitter.
 * @param lines the lines to send.
 */
private suspend fun emitLines(emit: suspend (String) -> Unit, lines: List<String>) {
    lines.forEach { line -> emit("data: ${line.replace("\n", " ")}\n\n") }
}

/**
 * Runs a poll loop until the client disconnects, sending keep-alive comments
 * while idle. The step returns whether it produced output.
 *
 * @param emit the raw event emitter, used for keep-alive comments.
 * @param step polls once; `true` when new lines were emitted.
 */
private suspend fun sseLoop(emit: suspend (String) -> Unit, step: suspend () -> Boolean) {
    var idle = 0
    while (true) {
        val produced = step()
        idle = if (produced) 0 else idle + 1
        if (idle >= STREAM_KEEPALIVE_POLLS) {
            idle = 0
            emit(": keep-alive\n\n")
        }
        kotlinx.coroutines.delay(STREAM_POLL_MS)
    }
}

/**
 * Finds the lines in [current] that were appended after [previous], matching
 * on the previous tail line (rotation falls back to the full window).
 *
 * @param previous the last window sent.
 * @param current the freshly read window.
 * @return the new lines, oldest first.
 */
private fun newLines(previous: List<String>, current: List<String>): List<String> {
    if (previous.isEmpty()) {
        return current
    }
    if (current == previous) {
        return emptyList()
    }
    val anchor = previous.last()
    val index = current.lastIndexOf(anchor)
    return if (index >= 0) current.drop(index + 1) else current
}

/**
 * The client IP a rate limiter keys on.
 *
 * @return the caller's remote host.
 */
private fun RoutingContext.clientIp(): String = call.request.origin.remoteHost

/**
 * Checks a rate limiter for the caller's IP, answering `429` when exceeded.
 *
 * @param limiter the limiter guarding this route.
 * @return `true` if the request may proceed; `false` after a `429` was written.
 */
private suspend fun RoutingContext.withinRateLimit(limiter: RateLimiter): Boolean {
    if (limiter.allow(clientIp())) {
        return true
    }
    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many requests, try again later"))
    return false
}

private fun io.ktor.server.routing.Route.publicAuthRoutes(dependencies: ControlDependencies) {
    post("/auth/request-code") {
        if (!withinRateLimit(dependencies.authRateLimiter)) return@post
        val request = call.receive<LoginRequest>()
        call.respond(dependencies.panelAuth.requestCode(request.name))
    }
    post("/auth/verify") {
        if (!withinRateLimit(dependencies.authRateLimiter)) return@post
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
    get("/health") {
        if (!authorize(dependencies, "helix.panel.overview")) return@get
        val health = dependencies.nodeHealth?.invoke()
        if (health == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("node health unavailable"))
        } else {
            call.respond(health)
        }
    }
}

private fun io.ktor.server.routing.Route.observabilityRoutes(dependencies: ControlDependencies) {
    get("/logs") {
        if (!authorize(dependencies, "helix.panel.logs")) return@get
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 300
        call.respond(LogsResponse(dependencies.logBuffer.tail(tail)))
    }
    get("/logs/stream") {
        if (!authorize(dependencies, "helix.panel.logs")) return@get
        streamSse { emit ->
            var offset = dependencies.logBuffer.offset()
            emitLines(emit, dependencies.logBuffer.tail(200))
            sseLoop(emit) {
                val lines = dependencies.logBuffer.since(offset)
                offset = dependencies.logBuffer.offset()
                emitLines(emit, lines)
                lines.isNotEmpty()
            }
        }
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
        val actor = call.request.queryParameters["actor"]
        val search = call.request.queryParameters["search"]
        call.respond(dependencies.audit.recent(limit, category, actor, search))
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

private fun io.ktor.server.routing.Route.translationRoutes(dependencies: ControlDependencies) {
    get("/translations") {
        if (!authorize(dependencies, "helix.panel.translations")) return@get
        call.respond(
            TranslationsView(
                languages = dependencies.languages.languages(),
                defaultLanguage = dependencies.languages.defaultLanguage(),
                entries = dependencies.messages.entries(),
            ),
        )
    }
    post("/translations") {
        if (!authorize(dependencies, "helix.panel.translations")) return@post
        val update = call.receive<TranslationUpdate>()
        val ok = if (update.reset) {
            dependencies.messages.reset(update.key, update.language)
        } else {
            dependencies.messages.set(update.key, update.language, update.value)
        }
        if (ok) {
            dependencies.messages.ownerOf(update.key)?.let(dependencies.onMessagesChanged)
            call.respond(MessageResponse("updated ${update.key} (${update.language})"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown translation ${update.key}"))
        }
    }
    delete("/translations/{key}") {
        if (!authorize(dependencies, "helix.panel.translations")) return@delete
        val key = call.parameters["key"].orEmpty()
        if (dependencies.messages.deleteKey(key)) {
            dependencies.messages.ownerOf(key)?.let(dependencies.onMessagesChanged)
            call.respond(MessageResponse("deleted $key"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown or default-backed key: $key"))
        }
    }
    post("/translations/languages") {
        if (!authorize(dependencies, "helix.panel.translations")) return@post
        val update = call.receive<LanguageUpdate>()
        val ok = if (update.default) {
            dependencies.languages.setDefaultLanguage(update.language)
        } else {
            dependencies.languages.addLanguage(update.language)
        }
        if (ok) {
            call.respond(MessageResponse("${if (update.default) "default set to" else "added"} ${update.language}"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid language: ${update.language}"))
        }
    }
    delete("/translations/languages/{language}") {
        if (!authorize(dependencies, "helix.panel.translations")) return@delete
        val language = call.parameters["language"].orEmpty()
        if (dependencies.languages.removeLanguage(language)) {
            call.respond(MessageResponse("removed $language"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot remove language: $language"))
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
    get("/proxy/whitelist") {
        if (!authorize(dependencies, "helix.panel.proxy")) return@get
        call.respond(WhitelistView(dependencies.whitelist.isEnabled(), dependencies.whitelist.all()))
    }
    post("/proxy/whitelist") {
        if (!authorize(dependencies, "helix.panel.proxy")) return@post
        val request = call.receive<WhitelistToggleRequest>()
        dependencies.whitelist.setEnabled(request.enabled)
        dependencies.eventLog.record(
            "proxy",
            if (request.enabled) "Whitelist enabled" else "Whitelist disabled",
            if (request.enabled) "warn" else "info",
        )
        call.respond(MessageResponse("whitelist ${if (request.enabled) "enabled" else "disabled"}"))
    }
    post("/proxy/whitelist/add") {
        if (!authorize(dependencies, "helix.panel.proxy")) return@post
        val request = call.receive<WhitelistEntryRequest>()
        if (dependencies.whitelist.add(request.player)) {
            call.respond(MessageResponse("added ${request.player}"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("already whitelisted: ${request.player}"))
        }
    }
    post("/proxy/whitelist/remove") {
        if (!authorize(dependencies, "helix.panel.proxy")) return@post
        val request = call.receive<WhitelistEntryRequest>()
        if (dependencies.whitelist.remove(request.player)) {
            call.respond(MessageResponse("removed ${request.player}"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("not whitelisted: ${request.player}"))
        }
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
    get("/services/{id}/logs/stream") {
        if (!authorize(dependencies, "helix.panel.services")) return@get
        val id = call.parameters["id"].orEmpty()
        streamSse { emit ->
            var last: List<String> = dependencies.manager.logs(id, STREAM_TAIL)
            emitLines(emit, last)
            sseLoop(emit) {
                val current = dependencies.manager.logs(id, STREAM_TAIL)
                val fresh = newLines(last, current)
                last = current
                emitLines(emit, fresh)
                fresh.isNotEmpty()
            }
        }
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

private fun io.ktor.server.routing.Route.backupRoutes(dependencies: ControlDependencies) {
    get("/backups") {
        if (!authorize(dependencies, "helix.panel.backups")) return@get
        call.respond(
            org.helix.node.backup.BackupsOverview(
                workspaces = dependencies.backups.workspaces(),
                backups = dependencies.backups.list(),
            ),
        )
    }
    post("/services/{id}/backups") {
        if (!authorize(dependencies, "helix.panel.backups")) return@post
        val id = call.parameters["id"].orEmpty()
        val info = dependencies.backups.create(id)
        dependencies.audit.record(
            "backup",
            call.principal<PanelPrincipal>()?.name ?: "anonymous",
            "created ${info.fileName} for $id",
        )
        call.respond(HttpStatusCode.Created, info)
    }
    post("/backups/{serviceId}/{file}/restore") {
        if (!authorize(dependencies, "helix.panel.backups")) return@post
        val serviceId = call.parameters["serviceId"].orEmpty()
        val file = call.parameters["file"].orEmpty()
        dependencies.backups.restore(serviceId, file)
        dependencies.audit.record(
            "backup",
            call.principal<PanelPrincipal>()?.name ?: "anonymous",
            "restored $file into $serviceId",
        )
        call.respond(MessageResponse("restored $file into $serviceId"))
    }
    delete("/backups/{serviceId}/{file}") {
        if (!authorize(dependencies, "helix.panel.backups")) return@delete
        val serviceId = call.parameters["serviceId"].orEmpty()
        val file = call.parameters["file"].orEmpty()
        if (dependencies.backups.delete(serviceId, file)) {
            call.respond(MessageResponse("deleted $serviceId/$file"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown backup: $serviceId/$file"))
        }
    }
}

private fun io.ktor.server.routing.Route.fileRoutes(dependencies: ControlDependencies) {
    get("/files/roots") {
        if (!authorize(dependencies, "helix.panel.files")) return@get
        call.respond(dependencies.files.roots())
    }
    get("/files/list") {
        if (!authorize(dependencies, "helix.panel.files")) return@get
        val root = call.request.queryParameters["root"].orEmpty()
        val path = call.request.queryParameters["path"].orEmpty()
        call.respond(dependencies.files.list(root, path))
    }
    get("/files/content") {
        if (!authorize(dependencies, "helix.panel.files")) return@get
        val root = call.request.queryParameters["root"].orEmpty()
        val path = call.request.queryParameters["path"].orEmpty()
        call.respond(dependencies.files.read(root, path))
    }
    put("/files/content") {
        if (!authorize(dependencies, "helix.panel.files")) return@put
        val request = call.receive<org.helix.node.files.FileWriteRequest>()
        dependencies.files.write(request.root, request.path, request.content)
        dependencies.audit.record(
            "files",
            call.principal<PanelPrincipal>()?.name ?: "anonymous",
            "wrote ${request.root}/${request.path}",
        )
        call.respond(MessageResponse("saved ${request.path}"))
    }
    delete("/files") {
        if (!authorize(dependencies, "helix.panel.files")) return@delete
        val root = call.request.queryParameters["root"].orEmpty()
        val path = call.request.queryParameters["path"].orEmpty()
        if (dependencies.files.delete(root, path)) {
            dependencies.audit.record(
                "files",
                call.principal<PanelPrincipal>()?.name ?: "anonymous",
                "deleted $root/$path",
            )
            call.respond(MessageResponse("deleted $path"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not found: $path"))
        }
    }
}

private fun io.ktor.server.routing.Route.playerRoutes(dependencies: ControlDependencies) {
    get("/players") {
        if (!authorize(dependencies, "helix.panel.players")) return@get
        call.respond(dependencies.playerRegistry.online())
    }
    get("/players/lookup") {
        if (!authorize(dependencies, "helix.panel.players")) return@get
        val name = call.request.queryParameters["name"].orEmpty()
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing name"))
            return@get
        }
        val online = dependencies.playerRegistry.find(name)
        call.respond(
            PlayerLookupView(
                name = name,
                online = online != null,
                uuid = online?.uuid,
                proxyServiceId = online?.proxyServiceId,
                joinedAtEpochMs = online?.joinedAtEpochMs,
                sources = dependencies.playerData.export(name),
            ),
        )
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
            add(call.principal<PanelPrincipal>()?.name ?: "anonymous")
            request.duration?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (request.value.isNotBlank()) add(request.value)
        }
        call.respond(
            dependencies.registry.invoke(ActionInvocation("ban.set", arguments, ActionSource.REST, actor = restActor())),
        )
    }
    get("/players/{name}/gdpr-export") {
        if (!requireAdmin(dependencies)) return@get
        val name = call.parameters["name"].orEmpty()
        val result = dependencies.registry.invoke(
            ActionInvocation("player.gdpr-export", listOf(name), ActionSource.REST),
        )
        if (result.success) {
            call.respondText(result.lines.first(), ContentType.Application.Json)
        } else {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(result.lines.firstOrNull() ?: "export failed"))
        }
    }
    post("/players/{name}/gdpr-delete") {
        if (!requireAdmin(dependencies)) return@post
        val name = call.parameters["name"].orEmpty()
        val result = dependencies.registry.invoke(
            ActionInvocation("player.gdpr-delete", listOf(name), ActionSource.REST),
        )
        call.respond(MessageResponse(result.lines.firstOrNull() ?: "done"))
    }
}

private fun RoutingContext.playerAction(action: String, player: String, value: String): ActionInvocation =
    ActionInvocation(
        action = action,
        arguments = if (value.isBlank()) listOf(player) else listOf(player, value),
        source = ActionSource.REST,
        actor = restActor(),
    )

/**
 * The real player name behind a session-authenticated REST call, for audit
 * attribution — `null` for the static admin token (kept as the generic `rest`
 * label) so break-glass usage is not misattributed to a made-up name. A named
 * admin session (a player holding `helix.admin`) still yields their real name.
 *
 * @return the calling player's name, or `null`.
 */
private fun RoutingContext.restActor(): String? =
    call.principal<PanelPrincipal>()?.takeUnless { it.viaStaticToken }?.name

private fun io.ktor.server.routing.Route.actionRoutes(dependencies: ControlDependencies) {
    get("/actions") {
        // The full action catalog is admin-only: it enumerates every action name and usage,
        // which a per-service bridge token has no business reading (the dashboard's per-addon
        // runner sources its actions from the already-permission-gated /addons route instead).
        if (!requireAdmin(dependencies)) return@get
        call.respond(dependencies.registry.descriptors())
    }
    post("/actions") {
        if (!withinRateLimit(dependencies.actionsRateLimiter)) return@post
        val invocation = call.receive<ActionInvocation>()
        val descriptor = dependencies.registry.descriptors().firstOrNull { it.name == invocation.action }
        val required = descriptor?.permission
        val authorized = if (required != null) authorize(dependencies, required) else requireAdmin(dependencies)
        if (!authorized) return@post
        call.respond(dependencies.registry.invoke(invocation.copy(source = ActionSource.REST, actor = restActor())))
    }
}

// deliberately unauthenticated: Minecraft clients download resource
// packs directly from these URLs (packs contain no secrets)
private fun io.ktor.server.routing.Route.packRoutes(dependencies: ControlDependencies) {
    get("/packs/network.zip") {
        val pack = dependencies.networkPack.packFile()
        if (pack == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no network resource pack"))
        } else {
            call.respondBytes(java.nio.file.Files.readAllBytes(pack), ContentType.Application.Zip)
        }
    }
    get("/packs/network.sha1") {
        val sha1 = dependencies.networkPack.sha1()
        if (sha1 == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no network resource pack"))
        } else {
            call.respondText(sha1)
        }
    }
    // per-addon packs, kept for backwards compatibility
    get("/packs/{file}") {
        val file = call.parameters["file"].orEmpty()
        val id = file.removeSuffix(".zip").removeSuffix(".sha1")
        val pack = dependencies.addonManager.resourcePack(id)
        if (pack == null || !java.nio.file.Files.exists(pack)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no resource pack for addon: $id"))
        } else if (file.endsWith(".sha1")) {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            val hash = digest.digest(java.nio.file.Files.readAllBytes(pack))
                .joinToString("") { "%02x".format(it) }
            call.respondText(hash)
        } else {
            call.respondBytes(java.nio.file.Files.readAllBytes(pack), ContentType.Application.Zip)
        }
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

/**
 * Applies a bridge-reported join/leave to the registry and the derived
 * native-permission/identity state, shared by the live `/internal/player-event`
 * route and the outage-recovery `/internal/player-roster` reconciliation.
 *
 * @param dependencies control API dependencies.
 * @param event the join or leave to apply.
 * @return `true` when the event type was known.
 */
internal fun applyPlayerEvent(dependencies: ControlDependencies, event: PlayerEvent): Boolean {
    if (!dependencies.playerRegistry.handle(event)) {
        return false
    }
    when (event.type) {
        "join" -> {
            dependencies.nativePermissions.update(event.name, event.permissions)
            dependencies.identityRegistry.recordJoin(event.name, event.uuid)
            val address = event.address
            val uuid = event.uuid
            if (!address.isNullOrBlank() && !uuid.isNullOrBlank()) {
                dependencies.addressHashes.record(uuid, address)
            }
        }
        "leave" -> dependencies.nativePermissions.clear(event.name)
    }
    return true
}

private fun io.ktor.server.routing.Route.internalRoutes(dependencies: ControlDependencies) {
    post("/internal/heartbeat") {
        val report = call.receive<HeartbeatReport>()
        if (!requireBridge(dependencies, report.serviceId)) return@post
        if (dependencies.manager.handleHeartbeat(report)) {
            call.respond(MessageResponse("ok"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown service: ${report.serviceId}"))
        }
    }
    get("/internal/routing") {
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        if (!requireBridge(dependencies, proxyServiceId)) return@get
        call.respond(
            dependencies.routing.snapshot(proxyServiceId).copy(
                networkName = dependencies.networkName(),
                maintenanceScreen = dependencies.proxyScreens.raw("screen.maintenance"),
                serverFullScreen = dependencies.proxyScreens.raw("screen.server_full"),
            ),
        )
    }
    post("/internal/join-check") {
        if (!requireBridge(dependencies)) return@post
        val request = call.receive<JoinRequest>()
        call.respond(dependencies.joinGates.evaluate(request))
    }
    // Deprecated: drains the same queue as the /internal/poll long-poll, as a second
    // uncoordinated consumer — kept only for stragglers still calling it, logging a warning so
    // it can be tracked down and retired. New bridges must use /internal/poll's ack cursor.
    get("/internal/commands") {
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        if (!requireBridge(dependencies, proxyServiceId)) return@get
        logger.warn(
            "Deprecated /internal/commands hit by proxy '{}' — commands are delivered via " +
                "/internal/poll's ack cursor now; this endpoint races with it and will be removed.",
            proxyServiceId,
        )
        call.respond(dependencies.commandQueue.drain(proxyServiceId))
    }
    get("/internal/poll") {
        val proxyServiceId = call.request.queryParameters["proxyServiceId"].orEmpty()
        if (!requireBridge(dependencies, proxyServiceId)) return@get
        val seenRouting = call.request.queryParameters["routingVersion"]?.toIntOrNull() ?: -1
        val seenCatalog = call.request.queryParameters["commandCatalogVersion"]?.toIntOrNull() ?: -1
        val ackUpTo = call.request.queryParameters["ackUpTo"]?.toLongOrNull() ?: 0L
        // The PREVIOUS response's commands are only now confirmed delivered — remove them before
        // computing what is still pending, so a lost response (proxy restart, connection reset)
        // never drops a command: it simply reappears on the retried poll instead.
        dependencies.commandQueue.acknowledge(proxyServiceId, ackUpTo)
        val hub = dependencies.proxyEvents
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (true) {
            val pending = dependencies.commandQueue.pending(proxyServiceId)
            val routing = hub.routingVersion.get()
            val catalog = hub.commandCatalogVersion.get()
            val changed = pending.isNotEmpty() || routing != seenRouting || catalog != seenCatalog
            if (changed || System.currentTimeMillis() >= deadline) {
                // The ack token MUST be derived from the snapshot actually going into this
                // response — a command enqueued after pending() was read is not in it, so a
                // token covering it would let the proxy's next poll ack a command it never saw.
                val token = dependencies.commandQueue.tokenFor(pending, ackUpTo)
                call.respond(ProxyPoll(pending.map { it.command }, routing, catalog, token))
                break
            }
            hub.await((deadline - System.currentTimeMillis()).coerceIn(1, POLL_RECHECK_MS))
        }
    }
    post("/internal/permission-check") {
        if (!requireBridge(dependencies)) return@post
        val request = call.receive<PermissionCheckRequest>()
        call.respond(PermissionDecision(dependencies.permissionService.check(request)))
    }
    get("/internal/permission-nodes") {
        if (!requireBridge(dependencies)) return@get
        call.respond(knownPermissionNodes(dependencies))
    }
    // Refreshes an already-online player's native permission snapshot after the advertised node
    // list changed, without going through /internal/player-event (which would re-fire join/leave
    // side effects for a player who never actually left).
    post("/internal/player-permissions") {
        if (!requireBridge(dependencies)) return@post
        val report = call.receive<PlayerPermissionsReport>()
        dependencies.nativePermissions.update(report.name, report.permissions)
        call.respond(MessageResponse("ok"))
    }
    // Lets a bridge resolve a player's full granted-node snapshot, to mirror it onto a
    // Bukkit PermissionAttachment (see HelixPermissionProvider) — a bridge route, so it
    // authenticates like every other /internal/* route, not as an admin.
    get("/internal/player-permissions") {
        if (!requireBridge(dependencies)) return@get
        val name = call.request.queryParameters["name"].orEmpty()
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing name"))
            return@get
        }
        val granted = knownPermissionNodes(dependencies).filter { node ->
            dependencies.permissionService.check(PermissionCheckRequest(name = name, permission = node))
        }
        call.respond(PlayerPermissionsSnapshot(name = name, granted = granted))
    }
    post("/internal/player-event") {
        val event = call.receive<PlayerEvent>()
        if (!requireBridge(dependencies, event.proxyServiceId.ifBlank { null })) return@post
        if (applyPlayerEvent(dependencies, event)) {
            call.respond(MessageResponse("ok"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown event type: ${event.type}"))
        }
    }
    get("/internal/players") {
        if (!requireBridge(dependencies)) return@get
        call.respond(dependencies.playerRegistry.online())
    }
    // Reconciles the node's roster with a proxy's actual current player list — recovers joins and
    // leaves missed while the node was unreachable, which would otherwise desync PlayerRegistry
    // (and the native-permission cache) until each affected player manually reconnects.
    post("/internal/player-roster") {
        val report = call.receive<PlayerRosterReport>()
        if (!requireBridge(dependencies, report.proxyServiceId.ifBlank { null })) return@post
        val reportedNames = report.players.map { it.name.lowercase() }.toSet()
        val onlineViaProxy = dependencies.playerRegistry.online().filter { it.proxyServiceId == report.proxyServiceId }
        val missingJoins = report.players.filter { it.name.lowercase() !in onlineViaProxy.map { p -> p.name.lowercase() }.toSet() }
        val missingLeaves = onlineViaProxy.filter { it.name.lowercase() !in reportedNames }
        missingJoins.forEach { player ->
            applyPlayerEvent(
                dependencies,
                PlayerEvent(type = "join", name = player.name, uuid = player.uuid, proxyServiceId = report.proxyServiceId),
            )
        }
        missingLeaves.forEach { player ->
            applyPlayerEvent(
                dependencies,
                PlayerEvent(type = "leave", name = player.name, uuid = player.uuid, proxyServiceId = report.proxyServiceId),
            )
        }
        if (missingJoins.isNotEmpty() || missingLeaves.isNotEmpty()) {
            logger.info(
                "Roster reconciliation for proxy '{}': +{} join(s), -{} leave(s) missed during an outage",
                report.proxyServiceId,
                missingJoins.size,
                missingLeaves.size,
            )
        }
        call.respond(MessageResponse("ok"))
    }
    get("/internal/player-commands") {
        if (!requireBridge(dependencies)) return@get
        call.respond(dependencies.playerCommands.commands())
    }
    post("/internal/player-command") {
        if (!requireBridge(dependencies)) return@post
        val request = call.receive<PlayerCommandRequest>()
        call.respond(dependencies.playerCommands.execute(request))
    }
    // Lets a Paper/Velocity component holding a per-service token invoke its own
    // bridge-invocable actions (e.g. a HXA's node-backed storage proxy) — unlike
    // POST /api/v1/actions, which only ever accepts the admin token or a
    // helix.admin session (see requireBridge's KDoc), so a per-service token could
    // never call it at all.
    post("/internal/action") {
        if (!requireBridge(dependencies)) return@post
        val invocation = call.receive<ActionInvocation>()
        call.respond(dependencies.bridgeActions.invoke(invocation))
    }
    post("/internal/display") {
        if (!requireBridge(dependencies)) return@post
        val request = call.receive<JoinRequest>()
        call.respond(dependencies.displayResolvers.resolve(request.name))
    }
    // Covers a full backend refresh cycle (every online player) with one HTTP call instead of
    // one POST /internal/display per player, cutting request volume on the default interval.
    post("/internal/display-bulk") {
        if (!requireBridge(dependencies)) return@post
        val request = call.receive<DisplayBulkRequest>()
        call.respond(request.names.associateWith { name -> dependencies.displayResolvers.resolve(name) })
    }
    get("/internal/translations") {
        if (!requireBridge(dependencies)) return@get
        val online = dependencies.playerRegistry.online().map { it.name.lowercase() }.toSet()
        val languageList = dependencies.languages.languages()
        call.respond(
            TranslationsSnapshot(
                defaultLanguage = dependencies.languages.defaultLanguage(),
                languages = languageList,
                playerLanguages = dependencies.languages.playerLanguages().filterKeys { it in online },
                values = dependencies.messages.effectiveTables(languageList),
            ),
        )
    }
    post("/internal/player-language") {
        if (!requireBridge(dependencies)) return@post
        val report = call.receive<PlayerLocaleReport>()
        dependencies.languages.applyClientLocale(report.name, report.locale)
        call.respond(MessageResponse("ok"))
    }
    get("/internal/pack") {
        if (!requireBridge(dependencies)) return@get
        call.respond(NetworkPackInfo(sha1 = dependencies.networkPack.sha1()))
    }
    get("/internal/bridge-values") {
        val serviceId = call.request.queryParameters["serviceId"]
        if (!requireBridge(dependencies, serviceId)) return@get
        // serviceId absent entirely (legacy/global caller): the full unfiltered set, as before.
        // serviceId given but no longer resolves (stopped/unknown service): fail closed with an
        // empty map instead of silently falling back to the unfiltered set, which would leak
        // every addon's values to a caller that could not be confirmed to belong to any task.
        val values = if (serviceId == null) {
            dependencies.bridgeValues.all()
        } else {
            val task = dependencies.manager.find(serviceId)?.task
            if (task == null) emptyMap() else dependencies.bridgeValues.all { owner -> task.isAddonActive(owner) }
        }
        call.respond(values)
    }
    // Lets a bridge cache the active ban list locally (with its own TTL) so a join can still be
    // denied for a KNOWN ban while the node is briefly unreachable, instead of failing wide open.
    // Addon-agnostic by design: proxies the bans addon's own `ban.export` JSON verbatim (empty
    // array when the addon is not installed) rather than the node knowing BanEntry's shape.
    get("/internal/ban-snapshot") {
        if (!requireBridge(dependencies)) return@get
        val result = dependencies.registry.invoke(ActionInvocation("ban.export", emptyList(), ActionSource.SYSTEM))
        call.respondText(
            if (result.success) result.lines.firstOrNull() ?: "[]" else "[]",
            ContentType.Application.Json,
        )
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
            controlModule(dependencies, isTls = false)
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
            module = { controlModule(dependencies, isTls = true) },
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
