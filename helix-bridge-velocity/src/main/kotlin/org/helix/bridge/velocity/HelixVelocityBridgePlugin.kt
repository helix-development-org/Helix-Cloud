package org.helix.bridge.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.helix.api.action.ActionDescriptor
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.player.PlayerEvent
import org.helix.api.proxy.JoinDecision
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.PermissionDecision
import org.helix.api.proxy.ProxyCommand
import org.helix.api.proxy.ProxyPoll
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
    private val registeredPlayerCommands = ConcurrentHashMap.newKeySet<String>()
    private var settings: BridgeSettings? = null
    private var client: NodeHttpClient? = null
    private var registry: BackendRegistry? = null
    private var heartbeatTask: ScheduledTask? = null

    @Volatile
    private var polling = false
    private var pollThread: Thread? = null

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
        // Heartbeat is the only periodic task; everything else (commands,
        // routing, player-command registration) is delivered instantly via
        // a long-poll the node answers the moment something changes.
        heartbeatTask = proxy.scheduler
            .buildTask(this, Runnable { sendHeartbeat(loaded, httpClient) })
            .delay(Duration.ofSeconds(1))
            .repeat(Duration.ofSeconds(5))
            .schedule()
        startPollLoop(loaded, httpClient, backendRegistry)
        logger.info("Helix bridge enabled for {} → {}", loaded.serviceId, loaded.controlUrl)
    }

    /**
     * Stops the heartbeat task and the poll loop.
     *
     * @param event the shutdown event.
     */
    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        heartbeatTask?.cancel()
        heartbeatTask = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun startPollLoop(settings: BridgeSettings, client: NodeHttpClient, registry: BackendRegistry) {
        polling = true
        pollThread = Thread {
            var routingVersion = -1
            var catalogVersion = -1
            while (polling) {
                try {
                    val body = client.getJsonLong(
                        "/api/v1/internal/poll?proxyServiceId=${settings.serviceId}" +
                            "&routingVersion=$routingVersion&commandCatalogVersion=$catalogVersion",
                    )
                    if (body == null) {
                        Thread.sleep(RECONNECT_DELAY_MS)
                        continue
                    }
                    val poll = json.decodeFromString<ProxyPoll>(body)
                    poll.commands.forEach(::executeCommand)
                    if (poll.routingVersion != routingVersion) {
                        syncRouting(settings, client, registry)
                        routingVersion = poll.routingVersion
                    }
                    if (poll.commandCatalogVersion != catalogVersion) {
                        syncPlayerCommands(settings, client)
                        catalogVersion = poll.commandCatalogVersion
                    }
                } catch (interrupt: InterruptedException) {
                    return@Thread
                } catch (failure: Exception) {
                    logger.warn("Helix poll failed, retrying: {}", failure.message)
                    runCatching { Thread.sleep(RECONNECT_DELAY_MS) }
                }
            }
        }.apply {
            name = "helix-poll-${settings.serviceId}"
            isDaemon = true
            start()
        }
    }

    /**
     * Reports the join to the node's player registry.
     *
     * @param event the post-login event.
     */
    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        reportPlayerEvent("join", event.player.username, event.player.uniqueId.toString())
    }

    /**
     * Reports the leave to the node's player registry.
     *
     * @param event the disconnect event.
     */
    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        reportPlayerEvent("leave", event.player.username, event.player.uniqueId.toString())
    }

    private fun reportPlayerEvent(type: String, name: String, uuid: String) {
        val activeSettings = settings ?: return
        val httpClient = client ?: return
        runCatching {
            val event = PlayerEvent(
                type = type,
                name = name,
                uuid = uuid,
                proxyServiceId = activeSettings.serviceId,
            )
            httpClient.postJson("/api/v1/internal/player-event", json.encodeToString(event))
        }.onFailure { logger.warn("Helix player event failed: {}", it.message) }
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

    private fun sendHeartbeat(settings: BridgeSettings, client: NodeHttpClient) {
        runCatching {
            val report = HeartbeatReport(
                serviceId = settings.serviceId,
                onlinePlayers = proxy.playerCount,
                maxPlayers = proxy.configuration.showMaxPlayers,
            )
            client.postJson("/api/v1/internal/heartbeat", json.encodeToString(report))
        }.onFailure { logger.warn("Helix heartbeat failed: {}", it.message) }
    }

    private fun syncRouting(settings: BridgeSettings, client: NodeHttpClient, registry: BackendRegistry) {
        runCatching {
            val body = client.getJson("/api/v1/internal/routing?proxyServiceId=${settings.serviceId}")
                ?: return@runCatching
            val snapshot = json.decodeFromString<RoutingSnapshot>(body)
            registry.sync(snapshot)
            maintenance.set(snapshot.maintenance)
        }.onFailure { logger.warn("Helix routing sync failed: {}", it.message) }
    }

    private fun syncPlayerCommands(settings: BridgeSettings, client: NodeHttpClient) {
        runCatching {
            val body = client.getJson("/api/v1/internal/player-commands") ?: return@runCatching
            json.decodeFromString<List<ActionDescriptor>>(body)
                .filter { it.playerCommand }
                .forEach { descriptor -> registerPlayerCommand(settings, client, descriptor) }
        }.onFailure { logger.warn("Helix player command sync failed: {}", it.message) }
    }

    private fun registerPlayerCommand(
        settings: BridgeSettings,
        client: NodeHttpClient,
        descriptor: ActionDescriptor,
    ) {
        if (!registeredPlayerCommands.add(descriptor.name)) {
            return
        }
        val manager = proxy.commandManager
        manager.register(
            manager.metaBuilder(descriptor.name).plugin(this).build(),
            com.velocitypowered.api.command.SimpleCommand { invocation ->
                val player = invocation.source() as? com.velocitypowered.api.proxy.Player
                    ?: return@SimpleCommand
                proxy.scheduler.buildTask(
                    this,
                    Runnable { executePlayerCommand(client, descriptor, player, invocation.arguments().toList()) },
                ).schedule()
            },
        )
        logger.info("Registered player command /{} → node action", descriptor.name)
    }

    private fun executePlayerCommand(
        client: NodeHttpClient,
        descriptor: ActionDescriptor,
        player: com.velocitypowered.api.proxy.Player,
        arguments: List<String>,
    ) {
        val response = runCatching {
            client.postJsonForBody(
                "/api/v1/internal/player-command",
                json.encodeToString(
                    org.helix.api.action.PlayerCommandRequest(
                        player = player.username,
                        command = descriptor.name,
                        arguments = arguments,
                    ),
                ),
            )
        }.onFailure { logger.warn("Player command /{} failed: {}", descriptor.name, it.message) }
            .getOrNull()
        if (response == null) {
            player.sendMessage(Component.text("Command is currently unavailable."))
            return
        }
        val result = json.decodeFromString<org.helix.api.action.ActionResult>(response)
        result.lines.ifEmpty { listOf(if (result.success) "Done." else "Failed.") }
            .forEach { line -> player.sendMessage(colored(line)) }
    }

    private fun executeCommand(command: ProxyCommand) {
        when (command.type) {
            "kick" -> proxy.getPlayer(command.player).ifPresent { player ->
                player.disconnect(Component.text(command.reason ?: "You were kicked from the network."))
                logger.info("Kicked {} ({})", command.player, command.reason ?: "no reason")
            }
            "message" -> proxy.getPlayer(command.player).ifPresent { player ->
                player.sendMessage(colored(command.reason ?: ""))
            }
            "broadcast" -> proxy.allPlayers.forEach { player ->
                player.sendMessage(colored(command.reason ?: ""))
            }
            else -> logger.warn("Unknown proxy command type: {}", command.type)
        }
    }

    private fun colored(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)

    private companion object {
        /** Delay before reconnecting the poll loop after a failure. */
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
