package org.helix.wire

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.PlayerCommandRequest
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.bridge.NetworkPackInfo
import org.helix.api.display.DisplayBulkRequest
import org.helix.api.display.DisplayProfile
import org.helix.api.i18n.TranslationsSnapshot
import org.helix.api.player.PlayerEvent
import org.helix.api.player.PlayerLocaleReport
import org.helix.api.player.PlayerPermissionsReport
import org.helix.api.player.PlayerRosterReport
import org.helix.api.proxy.JoinDecision
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.PermissionDecision
import org.helix.api.proxy.PlayerPermissionsSnapshot
import org.helix.api.proxy.ProxyPoll
import org.helix.api.proxy.RoutingSnapshot

/**
 * The typed node API a service (bridge or addon component) talks through —
 * the single place that knows how each internal call travels.
 *
 * Every method first tries the persistent Helix-Wire connection (when the
 * node handed out a `helix://` control url) and transparently falls back
 * to the equivalent HTTP `internal` endpoint while the wire is disabled or
 * down, so callers never care about the transport. Push events (the proxy
 * command/routing feed) only ever arrive over the wire; while it is down a
 * proxy keeps using [pollHttp], the classic long-poll.
 *
 * @property controlUrl the primary control url from `HELIX_CONTROL_URL` —
 *   `helix://`/`helixs://` when the wire is enabled, `http://` otherwise.
 * @property httpUrl the plain HTTP base url used for fallback, from
 *   `HELIX_CONTROL_HTTP_URL` when the wire is enabled, else [controlUrl].
 * @property serviceId this service's id.
 * @property token this service's per-service token.
 * @property warn sink for throttled transport warnings.
 */
class ServiceNodeApi(
    private val controlUrl: String,
    private val httpUrl: String,
    private val serviceId: String,
    private val token: String,
    private val warn: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val lastStatusByPath = ConcurrentHashMap<String, Int>()
    private val wire: WireClient? = buildWire()

    private fun buildWire(): WireClient? {
        val scheme = controlUrl.substringBefore("://", "").lowercase()
        if (scheme != "helix" && scheme != "helixs") {
            return null
        }
        val authority = controlUrl.substringAfter("://").substringBefore('/')
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "8090").toIntOrNull() ?: 8090
        val ssl = if (scheme == "helixs") javax.net.ssl.SSLContext.getDefault() else null
        return WireClient(host, port, serviceId, token, ssl)
    }

    /**
     * Starts the wire connection when one is configured; a plain HTTP setup
     * is a no-op.
     */
    fun start() {
        wire?.start()
    }

    /**
     * Closes the wire connection.
     */
    fun close() {
        wire?.close()
    }

    /**
     * Whether calls currently travel over the wire.
     *
     * @return `true` while the wire is connected.
     */
    fun isWireActive(): Boolean = wire?.isConnected() == true

    /**
     * Registers the callback for wire pushes (`poll` feed and future
     * categories); never fires on a pure HTTP setup.
     *
     * @param handler receives the push category and CBOR payload.
     */
    fun onPush(handler: (category: String, payload: ByteArray) -> Unit) {
        wire?.onPush(handler)
    }

    /**
     * Decodes a push payload.
     *
     * @param T the payload type.
     * @param payload the CBOR payload from [onPush].
     * @return the decoded value, or `null` when malformed.
     */
    inline fun <reified T> decodePush(payload: ByteArray): T? =
        runCatching { WireCodec.decode<T>(payload) }.getOrNull()

    // ------------------------------------------------------------------
    // Typed calls (wire first, HTTP fallback)
    // ------------------------------------------------------------------

    /**
     * Reports a heartbeat.
     *
     * @param report the heartbeat.
     * @return `true` when the node accepted it.
     */
    fun heartbeat(report: HeartbeatReport): Boolean =
        callOk("heartbeat", report, HeartbeatReport.serializer()) {
            postOk("/api/v1/internal/heartbeat", json.encodeToString(HeartbeatReport.serializer(), report))
        }

    /**
     * Fetches this proxy's routing snapshot.
     *
     * @return the snapshot, or `null` when the node is unreachable.
     */
    fun routing(): RoutingSnapshot? =
        call("routing", null, null, RoutingSnapshot.serializer()) {
            getJson("/api/v1/internal/routing?proxyServiceId=$serviceId", RoutingSnapshot.serializer())
        }

    /**
     * Evaluates the join gates for a joining player.
     *
     * @param request the join request.
     * @return the decision, or `null` when the node is unreachable.
     */
    fun joinCheck(request: JoinRequest): JoinDecision? =
        call("join-check", request, JoinRequest.serializer(), JoinDecision.serializer()) {
            postJson("/api/v1/internal/join-check", JoinRequest.serializer(), request, JoinDecision.serializer())
        }

    /**
     * Checks one permission.
     *
     * @param request the permission question.
     * @return the decision, or `null` when the node is unreachable.
     */
    fun permissionCheck(request: PermissionCheckRequest): PermissionDecision? =
        call("permission-check", request, PermissionCheckRequest.serializer(), PermissionDecision.serializer()) {
            postJson(
                "/api/v1/internal/permission-check",
                PermissionCheckRequest.serializer(),
                request,
                PermissionDecision.serializer(),
            )
        }

    /**
     * Lists the permission nodes the node knows.
     *
     * @return the nodes, or `null` when the node is unreachable.
     */
    fun permissionNodes(): List<String>? =
        call("permission-nodes", null, null, STRING_LIST) {
            getJson("/api/v1/internal/permission-nodes", STRING_LIST)
        }

    /**
     * Reports a player's native permission nodes.
     *
     * @param report the snapshot report.
     * @return `true` when accepted.
     */
    fun playerPermissionsSet(report: PlayerPermissionsReport): Boolean =
        callOk("player-permissions-set", report, PlayerPermissionsReport.serializer()) {
            postOk(
                "/api/v1/internal/player-permissions",
                json.encodeToString(PlayerPermissionsReport.serializer(), report),
            )
        }

    /**
     * Resolves a player's full granted-node snapshot.
     *
     * @param name the player name.
     * @return the snapshot, or `null` when the node is unreachable.
     */
    fun playerPermissionsGet(name: String): PlayerPermissionsSnapshot? =
        call("player-permissions-get", PlayerName(name), PlayerName.serializer(), PlayerPermissionsSnapshot.serializer()) {
            getJson(
                "/api/v1/internal/player-permissions?name=${encode(name)}",
                PlayerPermissionsSnapshot.serializer(),
            )
        }

    /**
     * Reports a join or leave.
     *
     * @param event the player event.
     * @return `true` when accepted.
     */
    fun playerEvent(event: PlayerEvent): Boolean =
        callOk("player-event", event, PlayerEvent.serializer()) {
            postOk("/api/v1/internal/player-event", json.encodeToString(PlayerEvent.serializer(), event))
        }

    /**
     * Reconciles the node's roster with this proxy's player list.
     *
     * @param report the roster report.
     * @return `true` when accepted.
     */
    fun playerRoster(report: PlayerRosterReport): Boolean =
        callOk("player-roster", report, PlayerRosterReport.serializer()) {
            postOk("/api/v1/internal/player-roster", json.encodeToString(PlayerRosterReport.serializer(), report))
        }

    /**
     * Lists the player-command catalog.
     *
     * @return the descriptors, or `null` when the node is unreachable.
     */
    fun playerCommands(): List<ActionDescriptor>? =
        call("player-commands", null, null, DESCRIPTOR_LIST) {
            getJson("/api/v1/internal/player-commands", DESCRIPTOR_LIST)
        }

    /**
     * Executes a player command on the node.
     *
     * @param request the command request.
     * @return the result, or `null` when the node is unreachable.
     */
    fun playerCommand(request: PlayerCommandRequest): ActionResult? =
        call("player-command", request, PlayerCommandRequest.serializer(), ActionResult.serializer()) {
            postJson(
                "/api/v1/internal/player-command",
                PlayerCommandRequest.serializer(),
                request,
                ActionResult.serializer(),
            )
        }

    /**
     * Invokes a bridge-invocable action.
     *
     * @param invocation the invocation.
     * @return the result, or `null` when the node is unreachable.
     */
    fun action(invocation: ActionInvocation): ActionResult? =
        call("action", invocation, ActionInvocation.serializer(), ActionResult.serializer()) {
            postJson("/api/v1/internal/action", ActionInvocation.serializer(), invocation, ActionResult.serializer())
        }

    /**
     * Resolves one player's display profile.
     *
     * @param name the player name.
     * @return the profile, or `null` when the node is unreachable.
     */
    fun display(name: String): DisplayProfile? =
        call("display", JoinRequest(name), JoinRequest.serializer(), DisplayProfile.serializer()) {
            postJson("/api/v1/internal/display", JoinRequest.serializer(), JoinRequest(name), DisplayProfile.serializer())
        }

    /**
     * Resolves many display profiles in one call.
     *
     * @param names the player names.
     * @return name to profile, or `null` when the node is unreachable.
     */
    fun displayBulk(names: List<String>): Map<String, DisplayProfile>? =
        call("display-bulk", DisplayBulkRequest(names), DisplayBulkRequest.serializer(), DISPLAY_MAP) {
            postJson("/api/v1/internal/display-bulk", DisplayBulkRequest.serializer(), DisplayBulkRequest(names), DISPLAY_MAP)
        }

    /**
     * Fetches the translations snapshot.
     *
     * @return the snapshot, or `null` when the node is unreachable.
     */
    fun translations(): TranslationsSnapshot? =
        call("translations", null, null, TranslationsSnapshot.serializer()) {
            getJson("/api/v1/internal/translations", TranslationsSnapshot.serializer())
        }

    /**
     * Reports a player's client locale.
     *
     * @param report the locale report.
     * @return `true` when accepted.
     */
    fun playerLanguage(report: PlayerLocaleReport): Boolean =
        callOk("player-language", report, PlayerLocaleReport.serializer()) {
            postOk("/api/v1/internal/player-language", json.encodeToString(PlayerLocaleReport.serializer(), report))
        }

    /**
     * Fetches the network resource-pack info.
     *
     * @return the info, or `null` when the node is unreachable.
     */
    fun pack(): NetworkPackInfo? =
        call("pack", null, null, NetworkPackInfo.serializer()) {
            getJson("/api/v1/internal/pack", NetworkPackInfo.serializer())
        }

    /**
     * Fetches this service's bridge-value snapshot.
     *
     * @return the values, or `null` when the node is unreachable.
     */
    fun bridgeValues(): Map<String, String>? =
        call("bridge-values", null, null, STRING_MAP) {
            getJson("/api/v1/internal/bridge-values?serviceId=$serviceId", STRING_MAP)
        }

    /**
     * Fetches the active ban list as the bans addon's raw JSON.
     *
     * @return the JSON text, or `null` when the node is unreachable.
     */
    fun banSnapshot(): String? {
        val overWire = call("ban-snapshot", null, null, RawJson.serializer()) { null }
        if (overWire != null) {
            return overWire.json
        }
        return getText("/api/v1/internal/ban-snapshot")
    }

    /**
     * Acknowledges wire-pushed proxy commands up to a sequence number.
     * Wire-only: the HTTP long-poll carries its cursor itself.
     *
     * @param ackUpTo highest applied command sequence.
     * @return `true` when acknowledged over the wire.
     */
    fun pollAck(ackUpTo: Long): Boolean =
        callOk("poll-ack", PollAck(ackUpTo), PollAck.serializer()) { false }

    /**
     * The classic HTTP long-poll, used while the wire is down.
     *
     * @param routingVersion last seen routing version.
     * @param catalogVersion last seen command-catalog version.
     * @param ackUpTo highest applied command sequence.
     * @return the poll result, or `null` when the node is unreachable.
     */
    fun pollHttp(routingVersion: Int, catalogVersion: Int, ackUpTo: Long): ProxyPoll? =
        getJson(
            "/api/v1/internal/poll?proxyServiceId=$serviceId&routingVersion=$routingVersion" +
                "&commandCatalogVersion=$catalogVersion&ackUpTo=$ackUpTo",
            ProxyPoll.serializer(),
            timeout = Duration.ofSeconds(40),
        )

    // ------------------------------------------------------------------
    // Transport plumbing
    // ------------------------------------------------------------------

    private fun <Q, R> call(
        endpoint: String,
        request: Q?,
        requestSerializer: KSerializer<Q>?,
        responseSerializer: KSerializer<R>,
        httpFallback: () -> R?,
    ): R? {
        val client = wire
        if (client != null && client.isConnected()) {
            val payload = if (request != null && requestSerializer != null) {
                WireCodec.encode(requestSerializer, request)
            } else {
                ByteArray(0)
            }
            val response = runCatching { client.request(endpoint, payload) }.getOrNull()
            if (response != null && response.ok) {
                return runCatching { WireCodec.decode(responseSerializer, response.body) }.getOrNull()
                    ?: httpFallback()
            }
            if (response != null) {
                warnOnce("wire:$endpoint", "wire call $endpoint failed: ${response.message}")
            }
        }
        return httpFallback()
    }

    private fun <Q> callOk(
        endpoint: String,
        request: Q,
        requestSerializer: KSerializer<Q>,
        httpFallback: () -> Boolean,
    ): Boolean {
        val client = wire
        if (client != null && client.isConnected()) {
            val response = runCatching {
                client.request(endpoint, WireCodec.encode(requestSerializer, request))
            }.getOrNull()
            if (response != null) {
                return response.ok
            }
        }
        return httpFallback()
    }

    private fun postOk(path: String, body: String): Boolean = send(post(path, body))?.let { it in 200..299 } ?: false

    private fun <Q, R> postJson(path: String, reqSer: KSerializer<Q>, request: Q, resSer: KSerializer<R>): R? {
        val response = sendForBody(post(path, json.encodeToString(reqSer, request))) ?: return null
        return runCatching { json.decodeFromString(resSer, response) }.getOrNull()
    }

    private fun <R> getJson(path: String, serializer: KSerializer<R>, timeout: Duration = Duration.ofSeconds(5)): R? {
        val body = getText(path, timeout) ?: return null
        return runCatching { json.decodeFromString(serializer, body) }.getOrNull()
    }

    private fun getText(path: String, timeout: Duration = Duration.ofSeconds(5)): String? =
        sendForBody(
            HttpRequest.newBuilder(URI.create(httpUrl.trimEnd('/') + path))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .GET()
                .build(),
        )

    private fun post(path: String, body: String): HttpRequest =
        HttpRequest.newBuilder(URI.create(httpUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun send(request: HttpRequest): Int? = runCatching {
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        trackStatus(request.uri().path, response.statusCode())
        response.statusCode()
    }.onFailure { warnOnce(request.uri().path, "node unreachable at ${request.uri()}: ${it.message}") }
        .getOrNull()

    private fun sendForBody(request: HttpRequest): String? = runCatching {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        trackStatus(request.uri().path, response.statusCode())
        if (response.statusCode() in 200..299) response.body() else null
    }.onFailure { warnOnce(request.uri().path, "node unreachable at ${request.uri()}: ${it.message}") }
        .getOrNull()

    private fun trackStatus(path: String, status: Int) {
        val previous = lastStatusByPath.put(path, status)
        if (status !in 200..299 && previous != status) {
            warn("node answered $status for $path")
        }
    }

    private fun warnOnce(key: String, message: String) {
        if (lastStatusByPath.put(key, -1) != -1) {
            warn(message)
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        private val STRING_LIST = ListSerializer(String.serializer())
        private val STRING_MAP = MapSerializer(String.serializer(), String.serializer())
        private val DESCRIPTOR_LIST = ListSerializer(ActionDescriptor.serializer())
        private val DISPLAY_MAP = MapSerializer(String.serializer(), DisplayProfile.serializer())

        /**
         * Builds the API from the wrapper-injected environment.
         *
         * @param env the environment map, injectable for tests.
         * @param warn sink for throttled transport warnings.
         * @return the API, or `null` outside a Helix-managed service.
         */
        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            warn: (String) -> Unit = {},
        ): ServiceNodeApi? {
            val serviceId = env["HELIX_SERVICE_ID"]?.takeIf { it.isNotBlank() } ?: return null
            val controlUrl = env["HELIX_CONTROL_URL"]?.takeIf { it.isNotBlank() } ?: return null
            val token = env["HELIX_CONTROL_TOKEN"]?.takeIf { it.isNotBlank() } ?: return null
            val httpUrl = env["HELIX_CONTROL_HTTP_URL"]?.takeIf { it.isNotBlank() } ?: controlUrl
            return ServiceNodeApi(controlUrl, httpUrl, serviceId, token, warn)
        }
    }
}
