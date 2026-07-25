package org.helix.bridge.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerPing
import java.util.UUID
import com.velocitypowered.api.scheduler.ScheduledTask
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.helix.api.action.ActionDescriptor
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.bridge.ResourceProbe
import org.helix.api.i18n.TranslationsSnapshot
import org.helix.api.message.LegacyToMini
import org.helix.api.player.PlayerEvent
import org.helix.api.player.PlayerLocaleReport
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
    private val miniMessage = MiniMessage.miniMessage()
    private val maintenance = AtomicBoolean(false)
    private val registeredPlayerCommands = ConcurrentHashMap.newKeySet<String>()
    private val translations = BridgeTranslations()
    private val reportedLocales = ConcurrentHashMap.newKeySet<String>()
    private var settings: BridgeSettings? = null
    private var client: NodeHttpClient? = null
    private var registry: BackendRegistry? = null
    private var heartbeatTask: ScheduledTask? = null

    @Volatile
    private var permissionNodes: List<String>? = null

    @Volatile
    private var networkName: String = ""

    @Volatile
    private var maintenanceScreen: String = ""

    @Volatile
    private var serverFullScreen: String = ""

    @Volatile
    private var motd: MotdData? = null

    @Volatile
    private var networkPrefix: String = ""

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
        ProxyCommands(proxy, backendRegistry, ::translate).register(this)
        // Heartbeat is the only periodic task; everything else (commands,
        // routing, player-command registration) is delivered instantly via
        // a long-poll the node answers the moment something changes.
        heartbeatTask = proxy.scheduler
            .buildTask(
                this,
                Runnable {
                    sendHeartbeat(loaded, httpClient)
                    syncBridgeValues(loaded, httpClient)
                    syncTranslations(httpClient)
                },
            )
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
        val granted = permissionNodes().filter { event.player.hasPermission(it) }
        reportPlayerEvent("join", event.player.username, event.player.uniqueId.toString(), granted)
    }

    /**
     * The Helix permission nodes to evaluate natively, fetched once and cached.
     *
     * @return the nodes advertised by the node, or empty on failure.
     */
    private fun permissionNodes(): List<String> {
        permissionNodes?.let { return it }
        val httpClient = client ?: return emptyList()
        val nodes = runCatching {
            httpClient.getJson("/api/v1/internal/permission-nodes")
                ?.let { json.decodeFromString<List<String>>(it) }
        }.onFailure { logger.warn("Helix permission-node fetch failed: {}", it.message) }
            .getOrNull() ?: emptyList()
        permissionNodes = nodes
        return nodes
    }

    /**
     * Reports the leave to the node's player registry.
     *
     * @param event the disconnect event.
     */
    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        reportedLocales.remove(event.player.username.lowercase())
        reportPlayerEvent("leave", event.player.username, event.player.uniqueId.toString())
    }

    private fun reportPlayerEvent(
        type: String,
        name: String,
        uuid: String,
        permissions: List<String> = emptyList(),
    ) {
        val activeSettings = settings ?: return
        val httpClient = client ?: return
        runCatching {
            val event = PlayerEvent(
                type = type,
                name = name,
                uuid = uuid,
                proxyServiceId = activeSettings.serviceId,
                permissions = permissions,
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
        val name = event.player.username
        if (proxy.playerCount >= proxy.configuration.showMaxPlayers &&
            !hasPermission(name, "helix.maintenance.bypass")
        ) {
            event.result = ResultedEvent.ComponentResult.denied(
                translate(
                    event.player,
                    "helix.translations.velocity.screen.server_full",
                    serverFullScreen.ifBlank { "The network is full." },
                ),
            )
            return
        }
        val decision = runCatching {
            val request = JoinRequest(name = name, uuid = event.player.uniqueId.toString())
            httpClient.postJsonForBody(
                "/api/v1/internal/join-check",
                json.encodeToString(request),
            )?.let { json.decodeFromString<JoinDecision>(it) }
        }.onFailure {
            logger.warn("Helix join gate failed for {} ({}): fail-open", name, it.message)
        }.getOrNull() ?: return
        if (!decision.allowed) {
            val message = decision.message
            event.result = ResultedEvent.ComponentResult.denied(
                if (message != null) {
                    screen(message, ctxFor(name))
                } else {
                    translate(event.player, "helix.translations.velocity.join.denied", "You may not join this network.")
                },
            )
            logger.info("Denied join of {} via {}: {}", name, activeSettings.serviceId, decision.message)
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
        val name = event.player.username
        if (maintenance.get() && !hasPermission(name, "helix.maintenance.bypass")) {
            event.player.disconnect(
                translate(
                    event.player,
                    "helix.translations.velocity.screen.maintenance",
                    maintenanceScreen.ifBlank { "The network is under maintenance." },
                ),
            )
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
            translate(
                event.player,
                "helix.translations.velocity.command.sent",
                "Sent to {server}.",
                mapOf("server" to fallback.serverInfo.name),
            ),
        )
    }

    /**
     * Reports the player's Minecraft client locale to the node, once per
     * connection, so first-joiners start in their client language.
     *
     * @param event the settings event.
     */
    @Subscribe
    fun onPlayerSettingsChanged(event: PlayerSettingsChangedEvent) {
        val httpClient = client ?: return
        val locale = event.playerSettings.locale ?: return
        if (!reportedLocales.add(event.player.username.lowercase())) {
            return
        }
        runCatching {
            httpClient.postJson(
                "/api/v1/internal/player-language",
                json.encodeToString(PlayerLocaleReport(event.player.username, locale.toString())),
            )
        }.onFailure { logger.warn("Helix locale report failed: {}", it.message) }
    }

    /**
     * Marks the server list during maintenance.
     *
     * @param event the ping event.
     */
    @Subscribe
    fun onProxyPing(event: ProxyPingEvent) {
        val profile = motd?.let { if (maintenance.get()) it.maintenance else it.normal }
        if (profile == null) {
            // No MOTD addon installed — keep the previous minimal behaviour.
            if (maintenance.get()) {
                event.ping = event.ping.asBuilder()
                    .description(translate(null, "helix.translations.velocity.motd.maintenance", "<red>Maintenance"))
                    .build()
            }
            return
        }
        val ctx = mapOf(
            "online" to proxy.playerCount.toString(),
            "max" to proxy.configuration.showMaxPlayers.toString(),
            "network" to networkName.ifBlank { "the network" },
            "prefix" to networkPrefix,
        )
        val builder = event.ping.asBuilder()
        val frame = profile.frameAt(System.currentTimeMillis())
        if (frame.line1.isNotBlank() || frame.line2.isNotBlank()) {
            builder.description(screen(listOf(frame.line1, frame.line2).filter { it.isNotBlank() }.joinToString("\n"), ctx))
        }
        if (profile.onlinePlayers >= 0) {
            builder.onlinePlayers(profile.onlinePlayers)
        }
        if (profile.maxPlayers >= 0) {
            builder.maximumPlayers(profile.maxPlayers)
        }
        if (profile.hover.isNotEmpty()) {
            builder.clearSamplePlayers()
            builder.samplePlayers(
                *profile.hover.map { line ->
                    ServerPing.SamplePlayer(legacyAmpersandToSection(applyContext(line, ctx)), UUID.randomUUID())
                }.toTypedArray(),
            )
        }
        if (profile.versionText.isNotBlank()) {
            builder.version(
                ServerPing.Version(
                    event.ping.version.protocol,
                    legacyAmpersandToSection(applyContext(profile.versionText, ctx)),
                ),
            )
        }
        event.ping = builder.build()
    }

    private fun applyContext(template: String, ctx: Map<String, String>): String {
        var text = template
        ctx.forEach { (key, value) -> text = text.replace("{$key}", value) }
        return text
    }

    private fun legacyAmpersandToSection(text: String): String {
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&' && i + 1 < text.length && LegacyToMini.isLegacyCode(text[i + 1])) {
                builder.append('§').append(text[i + 1])
                i += 2
            } else {
                builder.append(c)
                i++
            }
        }
        return builder.toString()
    }

    private fun syncBridgeValues(settings: BridgeSettings, client: NodeHttpClient) {
        runCatching {
            val body = client.getJson("/api/v1/internal/bridge-values?serviceId=${settings.serviceId}")
                ?: return@runCatching
            val values = json.decodeFromString<Map<String, String>>(body)
            motd = values["motd.config"]?.let { raw ->
                runCatching { json.decodeFromString<MotdData>(raw) }.getOrNull()
            }
            networkPrefix = values["network.prefix"] ?: ""
            values["network.name"]?.takeIf { it.isNotBlank() }?.let { networkName = it }
        }.onFailure { logger.warn("Helix bridge value sync failed: {}", it.message) }
    }

    private fun sendHeartbeat(settings: BridgeSettings, client: NodeHttpClient) {
        runCatching {
            val report = HeartbeatReport(
                serviceId = settings.serviceId,
                onlinePlayers = proxy.playerCount,
                maxPlayers = proxy.configuration.showMaxPlayers,
                memoryUsedMb = ResourceProbe.memoryUsedMb(),
                memoryMaxMb = ResourceProbe.memoryMaxMb(),
                cpuPercent = ResourceProbe.cpuPercent(),
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
            networkName = snapshot.networkName
            maintenanceScreen = snapshot.maintenanceScreen
            serverFullScreen = snapshot.serverFullScreen
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
            player.sendMessage(
                translate(player, "helix.translations.velocity.command.unavailable", "Command is currently unavailable."),
            )
            return
        }
        val result = json.decodeFromString<org.helix.api.action.ActionResult>(response)
        if (result.lines.isEmpty()) {
            val key = if (result.success) "command.result.done" else "command.result.failed"
            val fallback = if (result.success) "Done." else "Failed."
            player.sendMessage(translate(player, "helix.translations.velocity.$key", fallback))
            return
        }
        result.lines.forEach { line -> player.sendMessage(screen(line, ctxFor(player.username))) }
    }

    private fun executeCommand(command: ProxyCommand) {
        when (command.type) {
            "kick" -> proxy.getPlayer(command.player).ifPresent { player ->
                val text = command.translationKey
                    ?.let { commandComponent(command, player) }
                    ?: command.reason?.let { screen(it, ctxFor(player.username)) }
                    ?: translate(player, "helix.translations.velocity.kick.default", "You were kicked from the network.")
                player.disconnect(text)
                logger.info("Kicked {} ({})", command.player, command.reason ?: "no reason")
            }
            "message" -> proxy.getPlayer(command.player).ifPresent { player ->
                player.sendMessage(commandComponent(command, player))
            }
            "broadcast" -> proxy.allPlayers.forEach { player ->
                player.sendMessage(commandComponent(command, player))
            }
            else -> logger.warn("Unknown proxy command type: {}", command.type)
        }
    }

    /**
     * Renders a proxy command's text for one receiving player: resolves the
     * translation key in the player's language when present, otherwise the
     * plain text.
     *
     * @param command the proxy command.
     * @param player the receiving player.
     * @return the rendered component.
     */
    private fun commandComponent(command: ProxyCommand, player: Player): Component {
        val key = command.translationKey
        return if (key != null) {
            translate(player, key, command.reason ?: "", command.params)
        } else {
            screen(command.reason ?: "", ctxFor(player.username))
        }
    }

    /**
     * Renders a translation key in a player's language, with universal
     * placeholders plus [extra] substituted.
     *
     * @param player the receiving player, or `null` for the default language.
     * @param key flat translation key.
     * @param fallback template used while the key is not yet synced.
     * @param extra additional placeholder values.
     * @return the rendered component.
     */
    private fun translate(
        player: Player?,
        key: String,
        fallback: String,
        extra: Map<String, String> = emptyMap(),
    ): Component {
        val template = translations.resolve(key, player) ?: fallback
        return screen(template, ctxFor(player?.username ?: "") + extra)
    }

    private fun syncTranslations(client: NodeHttpClient) {
        runCatching {
            val body = client.getJson("/api/v1/internal/translations") ?: return@runCatching
            translations.update(json.decodeFromString<TranslationsSnapshot>(body))
        }.onFailure { logger.warn("Helix translation sync failed: {}", it.message) }
    }

    /**
     * Universal placeholder values every disconnect screen and message can use.
     *
     * @param player the affected player name.
     * @return placeholder name → value (proxy-level; domain values are already
     *  substituted by the addon that produced the text).
     */
    private fun ctxFor(player: String): Map<String, String> {
        val now = java.time.LocalDateTime.now()
        return mapOf(
            "player" to player,
            "network" to networkName.ifBlank { "the network" },
            "prefix" to networkPrefix,
            "server" to (proxy.getPlayer(player).flatMap { it.currentServer }
                .map { it.serverInfo.name }.orElse("")),
            "online" to proxy.playerCount.toString(),
            "max" to proxy.configuration.showMaxPlayers.toString(),
            "date" to now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")),
            "time" to now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
        )
    }

    /**
     * Renders a disconnect/message screen: fills the universal placeholders,
     * supports multiple lines and both MiniMessage tags and legacy `&` codes.
     *
     * @param template the raw screen template.
     * @param ctx universal placeholder values.
     * @return the rendered component.
     */
    private fun screen(template: String, ctx: Map<String, String>): Component {
        var text = template
        ctx.forEach { (key, value) -> text = text.replace("{$key}", value) }
        text = text.replace("\\n", "\n")
        return miniMessage.deserialize(LegacyToMini.translate(text))
    }

    private companion object {
        /** Delay before reconnecting the poll loop after a failure. */
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
