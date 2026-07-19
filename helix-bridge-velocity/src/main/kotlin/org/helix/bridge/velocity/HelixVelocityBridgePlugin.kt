package org.helix.bridge.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.proxy.JoinDecision
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.PermissionDecision
import org.helix.api.proxy.ProxyCommand
import org.helix.api.proxy.RoutingSnapshot
import org.slf4j.Logger

/**
 * Velocity-side bridge between the proxy and the Helix-Cloud node.
 *
 * Polls routing snapshots to register backends dynamically, reports
 * heartbeats, guarantees an initial server without any static
 * configuration, redirects kicked players and enforces maintenance.
 */
@Plugin(id = "helixbridge", name = "HelixVelocityBridge", version = "1.0.0")
class HelixVelocityBridgePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val maintenance = AtomicBoolean(false)
    private var settings: BridgeSettings? = null
    private var client: NodeHttpClient? = null
    private var registry: BackendRegistry? = null
    private var syncTask: ScheduledTask? = null

    /**
     * Boots the bridge after the proxy initialized.
     *
     * @param event the initialize event.
     */
    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val loaded = BridgeSettings.fromEnvironment()
        if (loaded == null) {
            logger.warn("No Helix environment found — bridge disabled.")
            return
        }
        settings = loaded
        val httpClient = NodeHttpClient(loaded)
        client = httpClient
        val backendRegistry = BackendRegistry(proxy, logger)
        registry = backendRegistry
        ProxyCommands(proxy, backendRegistry).register(this)
        syncTask = proxy.scheduler
            .buildTask(this, Runnable { sync(loaded, httpClient, backendRegistry) })
            .delay(Duration.ofSeconds(1))
            .repeat(Duration.ofSeconds(5))
            .schedule()
        logger.info("Helix bridge enabled for {} → {}", loaded.serviceId, loaded.controlUrl)
    }

    /**
     * Stops the sync scheduler.
     *
     * @param event the shutdown event.
     */
    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        syncTask?.cancel()
        syncTask = null
    }

    /**
     * Runs the generic Helix join gate before a player joins.
     *
     * The node evaluates every registered join-gate (typically provided by
     * addons such as the ban addon); this plugin only enforces the
     * aggregated decision and carries no addon-specific logic.
     *
     * @param event the login event.
     */
    @Subscribe
    fun onLogin(event: LoginEvent) {
        val activeSettings = settings ?: return
        val httpClient = client ?: return
        val decision = runCatching {
            val request = JoinRequest(
                name = event.player.username,
                uuid = event.player.uniqueId.toString(),
            )
            httpClient.postJsonForBody(
                "/api/v1/internal/join-check",
                json.encodeToString(request),
            )?.let { json.decodeFromString<JoinDecision>(it) }
        }.onFailure {
            logger.warn("Helix join gate failed for {} ({}): fail-open", event.player.username, it.message)
        }.getOrNull() ?: return
        if (!decision.allowed) {
            event.result = ResultedEvent.ComponentResult.denied(
                Component.text(decision.message ?: "You may not join this network."),
            )
            logger.info("Denied join of {} via {}: {}", event.player.username, activeSettings.serviceId, decision.message)
        }
    }

    /**
     * Chooses the least loaded fallback backend as initial server and
     * enforces maintenance.
     *
     * @param event the initial-server event.
     */
    @Subscribe
    fun onChooseInitialServer(event: PlayerChooseInitialServerEvent) {
        if (maintenance.get() && !hasPermission(event.player.username, "helix.maintenance.bypass")) {
            event.player.disconnect(Component.text("The network is under maintenance."))
            return
        }
        registry?.fallback()?.let(event::setInitialServer)
    }

    private fun hasPermission(player: String, permission: String): Boolean {
        val httpClient = client ?: return false
        return runCatching {
            httpClient.postJsonForBody(
                "/api/v1/internal/permission-check",
                json.encodeToString(PermissionCheckRequest(name = player, permission = permission)),
            )?.let { json.decodeFromString<PermissionDecision>(it).allowed } ?: false
        }.onFailure {
            logger.warn("Helix permission check failed for {}: {}", player, it.message)
        }.getOrDefault(false)
    }

    /**
     * Redirects kicked players to a fallback backend when possible.
     *
     * @param event the kick event.
     */
    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        val fallback = registry?.fallback(exclude = event.server.serverInfo.name) ?: return
        event.result = KickedFromServerEvent.RedirectPlayer.create(
            fallback,
            Component.text("Sent to ${fallback.serverInfo.name}."),
        )
    }

    /**
     * Marks the server list during maintenance.
     *
     * @param event the ping event.
     */
    @Subscribe
    fun onProxyPing(event: ProxyPingEvent) {
        if (maintenance.get()) {
            event.ping = event.ping.asBuilder()
                .description(Component.text("§cMaintenance"))
                .build()
        }
    }

    private fun sync(settings: BridgeSettings, client: NodeHttpClient, registry: BackendRegistry) {
        runCatching {
            val report = HeartbeatReport(
                serviceId = settings.serviceId,
                onlinePlayers = proxy.playerCount,
                maxPlayers = proxy.configuration.showMaxPlayers,
            )
            client.postJson("/api/v1/internal/heartbeat", json.encodeToString(report))
        }.onFailure { logger.warn("Helix heartbeat failed: {}", it.message) }
        runCatching {
            val body = client.getJson("/api/v1/internal/routing?proxyServiceId=${settings.serviceId}")
                ?: return@runCatching
            val snapshot = json.decodeFromString<RoutingSnapshot>(body)
            registry.sync(snapshot)
            maintenance.set(snapshot.maintenance)
        }.onFailure { logger.warn("Helix routing sync failed: {}", it.message) }
        runCatching {
            val body = client.getJson("/api/v1/internal/commands?proxyServiceId=${settings.serviceId}")
                ?: return@runCatching
            json.decodeFromString<List<ProxyCommand>>(body).forEach(::executeCommand)
        }.onFailure { logger.warn("Helix command poll failed: {}", it.message) }
    }

    private fun executeCommand(command: ProxyCommand) {
        when (command.type) {
            "kick" -> proxy.getPlayer(command.player).ifPresent { player ->
                player.disconnect(Component.text(command.reason ?: "You were kicked from the network."))
                logger.info("Kicked {} ({})", command.player, command.reason ?: "no reason")
            }
            else -> logger.warn("Unknown proxy command type: {}", command.type)
        }
    }
}
