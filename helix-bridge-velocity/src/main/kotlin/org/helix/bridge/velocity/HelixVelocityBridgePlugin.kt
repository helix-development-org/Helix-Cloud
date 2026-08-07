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
import com.velocitypowered.api.scheduler.ScheduledTask
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.helix.api.action.ActionDescriptor
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.bridge.ResourceProbe
import org.helix.api.message.LegacyToMini
import org.helix.api.player.PlayerEvent
import org.helix.api.player.PlayerLocaleReport
import org.helix.api.player.PlayerPermissionsReport
import org.helix.api.player.PlayerRosterReport
import org.helix.api.player.RosterPlayer
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.ProxyCommand
import org.helix.api.proxy.ProxyPoll
import org.helix.wire.ServiceNodeApi
import org.slf4j.Logger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private var api: ServiceNodeApi? = null
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

    /** Hex SHA-1 of the node's merged network resource pack, `null` while none exists. */
    @Volatile
    private var networkPackSha1: String? = null

    /** Operator-configured pack URL (`network.packurl`), via bridge values. */
    @Volatile
    private var configuredPackUrl: String? = null

    @Volatile
    private var polling = false
    private var pollThread: Thread? = null

    // Locally cached ban list (name -> expiry), refreshed every heartbeat cycle. Consulted only
    // when the live join-check call itself fails (node unreachable), so a known ban still holds
    // through a restart window instead of the join failing wide open.
    @Volatile
    private var banSnapshot: List<CachedBan> = emptyList()

    @Volatile
    private var banSnapshotAt: Long = 0L

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
        val nodeApi = ServiceNodeApi(loaded.controlUrl, loaded.httpUrl, loaded.serviceId, loaded.token) { logger.warn(it) }
        api = nodeApi
        nodeApi.start()
        val backendRegistry = BackendRegistry(proxy, logger)
        registry = backendRegistry
        ProxyCommands(proxy, backendRegistry, ::translate) { player ->
            hasPermission(player.username, "helix.maintenance.bypass")
        }.register(this)
        // Heartbeat is the only periodic task; everything else (commands,
        // routing, player-command registration) is delivered instantly — over
        // the wire push when it is up, otherwise via the classic long-poll.
        heartbeatTask = proxy.scheduler
            .buildTask(
                this,
                Runnable {
                    sendHeartbeat(nodeApi)
                    syncBridgeValues(nodeApi)
                    syncTranslations(nodeApi)
                    syncNetworkPack(nodeApi)
                    syncBanSnapshot(nodeApi)
                    syncRoster(loaded, nodeApi)
                },
            )
            .delay(Duration.ofSeconds(1))
            .repeat(Duration.ofSeconds(5))
            .schedule()
        // Wire push applies the command/routing feed the instant it arrives.
        nodeApi.onPush { category, payload ->
            if (category == "poll") {
                nodeApi.decodePush<ProxyPoll>(payload)?.let { applyPoll(loaded, nodeApi, backendRegistry, it) }
            }
        }
        startPollLoop(loaded, nodeApi, backendRegistry)
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
        api?.close()
        api = null
    }

    // Applied routing/catalog versions, shared between the wire-push handler and the
    // HTTP long-poll fallback so whichever transport is active continues where the other left off.
    @Volatile
    private var appliedRoutingVersion = -1

    @Volatile
    private var appliedCatalogVersion = -1

    /**
     * Applies one poll result (from the wire push or the HTTP long-poll):
     * runs its commands, re-syncs routing and the player-command catalog on
     * a version change, and acknowledges the commands so the node clears
     * them — over the wire when it is up.
     */
    private fun applyPoll(settings: BridgeSettings, api: ServiceNodeApi, registry: BackendRegistry, poll: ProxyPoll) {
        poll.commands.forEach(::executeCommand)
        if (poll.ackToken > 0 && api.isWireActive()) {
            api.pollAck(poll.ackToken)
        }
        if (poll.routingVersion != appliedRoutingVersion) {
            syncRouting(settings, api, registry)
            reapplyPermissionNodes()
            appliedRoutingVersion = poll.routingVersion
        }
        if (poll.commandCatalogVersion != appliedCatalogVersion) {
            syncPlayerCommands(settings, api)
            reapplyPermissionNodes()
            appliedCatalogVersion = poll.commandCatalogVersion
        }
    }

    private fun startPollLoop(settings: BridgeSettings, api: ServiceNodeApi, registry: BackendRegistry) {
        polling = true
        pollThread = Thread {
            // Confirms the PREVIOUS response's commands were received; the node only removes them
            // from its queue on this ack, so a response lost in transit (proxy restart, connection
            // reset) never silently drops a command — it simply reappears on the retried poll.
            var ackUpTo = 0L
            while (polling) {
                try {
                    // While the wire is up, the push handler delivers the feed; the HTTP long-poll
                    // stands down to avoid double-applying commands, and simply idles.
                    if (api.isWireActive()) {
                        Thread.sleep(RECONNECT_DELAY_MS)
                        continue
                    }
                    val poll = api.pollHttp(appliedRoutingVersion, appliedCatalogVersion, ackUpTo)
                    if (poll == null) {
                        Thread.sleep(RECONNECT_DELAY_MS)
                        continue
                    }
                    ackUpTo = poll.ackToken
                    applyPoll(settings, api, registry, poll)
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
     * Reports the join to the node's player registry and offers the merged
     * network resource pack.
     *
     * @param event the post-login event.
     */
    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        val granted = permissionNodes().filter { event.player.hasPermission(it) }
        val address = runCatching { event.player.remoteAddress?.address?.hostAddress }.getOrNull()
        reportPlayerEvent("join", event.player.username, event.player.uniqueId.toString(), granted, address)
    }

    /**
     * Offers the network pack once the player reached their first backend.
     *
     * Deliberately not at [onPostLogin]: since the 1.20.2 configuration
     * phase, an offer sent before the first server connection is silently
     * dropped by some client versions — the player then never sees the
     * pack prompt.
     *
     * @param event the post-connect event.
     */
    @Subscribe
    fun onServerPostConnect(event: com.velocitypowered.api.event.player.ServerPostConnectEvent) {
        if (event.previousServer == null) {
            sendNetworkPack(event.player)
        }
    }

    /**
     * Logs the client's answer to the pack offer, so "player got no pack"
     * reports are diagnosable from the proxy log.
     *
     * @param event the status event.
     */
    @Subscribe
    fun onResourcePackStatus(event: com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent) {
        logger.info("Network pack status for {}: {}", event.player.username, event.status)
    }

    /**
     * The Helix permission nodes to evaluate natively, fetched once and cached.
     *
     * A failed fetch is never cached — an empty result would otherwise stick
     * forever after a node hiccup at the very first call — so the next
     * opportunity (the next join, or [reapplyPermissionNodes]) retries.
     *
     * @return the nodes advertised by the node, or empty while none are cached yet.
     */
    private fun permissionNodes(): List<String> {
        permissionNodes?.let { return it }
        return fetchPermissionNodes() ?: emptyList()
    }

    /**
     * Fetches the permission-node list from the node and caches it only on
     * success.
     *
     * @return the fetched nodes, or `null` on failure (nothing cached).
     */
    private fun fetchPermissionNodes(): List<String>? {
        val nodeApi = api ?: return null
        val nodes = nodeApi.permissionNodes()
        if (nodes != null) {
            permissionNodes = nodes
        }
        return nodes
    }

    /**
     * Re-fetches the permission-node list and re-reports every online
     * player's granted set — called whenever the long-poll signals the
     * command catalog or routing changed, since either may mean the set of
     * natively-evaluated nodes changed too. Uses the dedicated
     * player-permissions endpoint so this never re-fires join/leave side
     * effects for players who never left.
     */
    private fun reapplyPermissionNodes() {
        val nodeApi = api ?: return
        val nodes = fetchPermissionNodes() ?: return
        proxy.allPlayers.forEach { player ->
            val granted = nodes.filter { player.hasPermission(it) }
            if (!nodeApi.playerPermissionsSet(PlayerPermissionsReport(player.username, granted))) {
                logger.warn("Helix permission reapply failed for {}", player.username)
            }
        }
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
        address: String? = null,
    ) {
        val activeSettings = settings ?: return
        val nodeApi = api ?: return
        nodeApi.playerEvent(
            PlayerEvent(
                type = type,
                name = name,
                uuid = uuid,
                proxyServiceId = activeSettings.serviceId,
                permissions = permissions,
                address = address,
            ),
        )
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
        val nodeApi = api ?: return
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
        val decision = nodeApi.joinCheck(JoinRequest(name = name, uuid = event.player.uniqueId.toString())) ?: run {
            // The live check failed (node unreachable) — consult the cached ban snapshot so a
            // player already known to be banned still cannot slip through the restart window;
            // anyone not on that (fresh enough) cached list still joins as before (fail-open).
            val cachedReason = cachedBan(name)
            if (cachedReason != null) {
                event.result = ResultedEvent.ComponentResult.denied(
                    translate(
                        event.player,
                        "helix.translations.velocity.join.denied_cached",
                        "You are banned from this network ({reason}). The panel is temporarily unreachable to confirm details.",
                        mapOf("reason" to cachedReason),
                    ),
                )
                logger.info("Denied join of {} via {}: cached ban snapshot ({})", name, activeSettings.serviceId, cachedReason)
            }
            return
        }
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
        val bypass = hasPermission(name, "helix.maintenance.bypass")
        if (maintenance.get() && !bypass) {
            event.player.disconnect(
                translate(
                    event.player,
                    "helix.translations.velocity.screen.maintenance",
                    maintenanceScreen.ifBlank { "The network is under maintenance." },
                ),
            )
            return
        }
        registry?.fallback(bypassMaintenance = bypass)?.let(event::setInitialServer)
    }

    private fun hasPermission(player: String, permission: String): Boolean {
        val nodeApi = api ?: return false
        return nodeApi.permissionCheck(PermissionCheckRequest(name = player, permission = permission))?.allowed ?: false
    }

    /**
     * Redirects kicked players to a fallback backend when possible.
     *
     * @param event the kick event.
     */
    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        val bypass = hasPermission(event.player.username, "helix.maintenance.bypass")
        val fallback = registry?.fallback(exclude = event.server.serverInfo.name, bypassMaintenance = bypass) ?: return
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
        val nodeApi = api ?: return
        val locale = event.playerSettings.locale ?: return
        if (!reportedLocales.add(event.player.username.lowercase())) {
            return
        }
        nodeApi.playerLanguage(PlayerLocaleReport(event.player.username, locale.toString()))
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

    private fun syncBridgeValues(api: ServiceNodeApi) {
        runCatching {
            val values = api.bridgeValues() ?: return@runCatching
            motd = values["motd.config"]?.let { raw ->
                runCatching { json.decodeFromString<MotdData>(raw) }.getOrNull()
            }
            networkPrefix = values["network.prefix"] ?: ""
            values["network.name"]?.takeIf { it.isNotBlank() }?.let { networkName = it }
            // 0.0.0.0 is a bind address, never something a client can download from
            configuredPackUrl = values["network.pack_url"]
                ?.takeIf { it.isNotBlank() && !it.contains("0.0.0.0") }
        }.onFailure { logger.warn("Helix bridge value sync failed: {}", it.message) }
    }

    /**
     * Refreshes the locally cached ban snapshot. Addon-agnostic on purpose: the node proxies the
     * bans addon's own export verbatim (an empty array without it), so this bridge never needs to
     * know the ban entry's shape beyond `player`/`expiresAtEpochMs`.
     */
    private fun syncBanSnapshot(api: ServiceNodeApi) {
        runCatching {
            val body = api.banSnapshot() ?: return@runCatching
            banSnapshot = json.decodeFromString<List<CachedBan>>(body)
            banSnapshotAt = System.currentTimeMillis()
        }.onFailure { logger.warn("Helix ban snapshot sync failed: {}", it.message) }
    }

    /**
     * Looks up [name] in the cached ban snapshot, honoring the cache's TTL and each entry's
     * expiry. Used only as a fallback when the live join-check call could not reach the node.
     *
     * @param name player name to look up (matched case-insensitively).
     * @return the reason the player is (still) banned per the cache, or `null` when the cache is
     *   stale or carries no matching active ban.
     */
    private fun cachedBan(name: String): String? {
        if (System.currentTimeMillis() - banSnapshotAt > BAN_SNAPSHOT_TTL_MILLIS) return null
        val now = System.currentTimeMillis()
        return banSnapshot.firstOrNull { it.player.equals(name, ignoreCase = true) && (it.expiresAtEpochMs == null || it.expiresAtEpochMs > now) }
            ?.reason
    }

    /**
     * Reports this proxy's complete current player list, so the node can
     * reconcile its player registry against reality — joins/leaves missed
     * while the node was unreachable would otherwise desync it until each
     * affected player manually reconnects.
     *
     * @param settings bridge settings (carries this proxy's service id).
     * @param client the node HTTP client.
     */
    private fun syncRoster(settings: BridgeSettings, api: ServiceNodeApi) {
        val players = proxy.allPlayers.map { RosterPlayer(it.username, it.uniqueId.toString()) }
        api.playerRoster(PlayerRosterReport(settings.serviceId, players))
    }

    /**
     * Fetches the network pack state and, when its SHA-1 changed since the
     * last sync, re-offers the pack to every online player.
     *
     * @param client the node HTTP client.
     */
    private fun syncNetworkPack(api: ServiceNodeApi) {
        val info = api.pack() ?: return
        if (info.sha1 == networkPackSha1) {
            return
        }
        networkPackSha1 = info.sha1
        if (info.sha1 != null) {
            logger.info("Network pack changed (sha1 {}), re-sending to {} player(s)", info.sha1, proxy.playerCount)
            proxy.allPlayers.forEach(::sendNetworkPack)
        }
    }

    /**
     * Offers the merged network resource pack to a player; a no-op while
     * the node serves no pack.
     *
     * @param player the receiving player.
     */
    private fun sendNetworkPack(player: Player) {
        val sha1 = networkPackSha1?.takeIf { it.length == SHA1_HEX_LENGTH }
        if (sha1 == null) {
            logger.info("No network pack (yet) — nothing offered to {}", player.username)
            return
        }
        val url = packUrl(player) ?: return
        logger.info("Offering network pack {} to {} from {}", sha1.take(8), player.username, url)
        runCatching {
            player.sendResourcePackOffer(
                proxy.createResourcePackBuilder(url)
                    .setHash(sha1.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    .setShouldForce(false)
                    .build(),
            )
        }.onFailure { logger.warn("Sending the network pack to {} failed: {}", player.username, it.message) }
    }

    /**
     * Resolves the pack URL the player's CLIENT can reach: the configured
     * `network.packurl` value, then the `HELIX_PACK_URL` env override, then
     * the address the player connected with (virtual host — control URLs
     * like `host.docker.internal` or `127.0.0.1` mean nothing to a remote
     * client), and finally the raw control URL.
     *
     * @param player the receiving player.
     * @return a download URL, or `null` without a node connection.
     */
    private fun packUrl(player: Player): String? {
        val activeSettings = settings ?: return null
        val controlPort = runCatching { java.net.URI(activeSettings.httpUrl).port }
            .getOrDefault(-1).takeIf { it > 0 } ?: DEFAULT_CONTROL_PORT
        configuredPackUrl?.let { return expandPackUrl(it, controlPort) }
        System.getenv("HELIX_PACK_URL")?.let { return expandPackUrl(it, controlPort) }
        val clientHost = player.virtualHost.map { it.hostString }.orElse(null)
            ?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "127.0.0.1" }
        if (clientHost != null) {
            return expandPackUrl(clientHost, controlPort)
        }
        return activeSettings.httpUrl + PACK_PATH
    }

    /**
     * Expands operator input into a full download URL: a bare host or ip
     * gets the control port and the pack path appended, a base URL just
     * the path.
     *
     * @param value full URL, `host:port` or bare host/ip.
     * @param controlPort port of the control API.
     * @return a complete pack URL.
     */
    private fun expandPackUrl(value: String, controlPort: Int): String {
        val base = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            val hostPort = if (':' in value) value else "$value:$controlPort"
            "http://$hostPort"
        }
        return if (base.contains("/api/")) base else base.trimEnd('/') + PACK_PATH
    }

    private fun sendHeartbeat(api: ServiceNodeApi) {
        val activeSettings = settings ?: return
        api.heartbeat(
            HeartbeatReport(
                serviceId = activeSettings.serviceId,
                onlinePlayers = proxy.playerCount,
                maxPlayers = proxy.configuration.showMaxPlayers,
                memoryUsedMb = ResourceProbe.memoryUsedMb(),
                memoryMaxMb = ResourceProbe.memoryMaxMb(),
                cpuPercent = ResourceProbe.cpuPercent(),
            ),
        )
    }

    private fun syncRouting(settings: BridgeSettings, api: ServiceNodeApi, registry: BackendRegistry) {
        val snapshot = api.routing() ?: return
        registry.sync(snapshot)
        maintenance.set(snapshot.maintenance)
        networkName = snapshot.networkName
        maintenanceScreen = snapshot.maintenanceScreen
        serverFullScreen = snapshot.serverFullScreen
    }

    private fun syncPlayerCommands(settings: BridgeSettings, api: ServiceNodeApi) {
        val commands = api.playerCommands() ?: return
        commands.filter { it.playerCommand }.forEach { descriptor -> registerPlayerCommand(settings, api, descriptor) }
    }

    private fun registerPlayerCommand(
        settings: BridgeSettings,
        api: ServiceNodeApi,
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
                    Runnable { executePlayerCommand(api, descriptor, player, invocation.arguments().toList()) },
                ).schedule()
            },
        )
        logger.info("Registered player command /{} → node action", descriptor.name)
    }

    private fun executePlayerCommand(
        api: ServiceNodeApi,
        descriptor: ActionDescriptor,
        player: com.velocitypowered.api.proxy.Player,
        arguments: List<String>,
    ) {
        val result = api.playerCommand(
            org.helix.api.action.PlayerCommandRequest(
                player = player.username,
                command = descriptor.name,
                arguments = arguments,
            ),
        )
        if (result == null) {
            player.sendMessage(
                translate(player, "helix.translations.velocity.command.unavailable", "Command is currently unavailable."),
            )
            return
        }
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

    private fun syncTranslations(api: ServiceNodeApi) {
        api.translations()?.let(translations::update)
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
            "server" to (
                proxy.getPlayer(player).flatMap { it.currentServer }
                .map { it.serverInfo.name }.orElse("")
            ),
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

        /** Download path of the merged network pack on the control API. */
        const val PACK_PATH = "/api/v1/packs/network.zip"

        /** Control API port assumed when the control URL names none. */
        const val DEFAULT_CONTROL_PORT = 8080

        /** Length of a hex-encoded SHA-1 hash. */
        const val SHA1_HEX_LENGTH = 40

        /**
         * How long the cached ban snapshot is trusted after its last successful refresh (heartbeat
         * cycle is 5s; this covers several missed cycles without trusting a stale list forever).
         */
        const val BAN_SNAPSHOT_TTL_MILLIS = 120_000L
    }
}
