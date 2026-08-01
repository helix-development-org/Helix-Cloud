package org.helix.node.launcher

import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import org.helix.api.action.ActionSource
import org.helix.api.execution.ExecutorType
import org.helix.node.actions.ActionRegistry
import org.helix.node.actions.BuiltinActions
import org.helix.node.addons.AddonActions
import org.helix.api.message.GlobalPlaceholders
import org.helix.node.audit.AuditLog
import org.helix.node.backup.BackupActions
import org.helix.node.backup.BackupService
import org.helix.node.files.FileManagerService
import org.helix.node.control.auth.PanelAuthService
import org.helix.node.addons.AddonManager
import org.helix.node.cli.NodeCli
import org.helix.node.config.NodeConfig
import org.helix.node.config.NodeConfigLoader
import org.helix.node.control.ControlDependencies
import org.helix.node.control.ControlServer
import org.helix.node.control.auth.ServiceTokenRegistry
import org.helix.node.dashboard.DashboardPanelRegistry
import org.helix.node.display.BridgeValueStore
import org.helix.node.display.DisplayResolverRegistry
import org.helix.node.events.EventLog
import org.helix.node.logging.LogBuffer
import kotlinx.serialization.json.Json
import org.helix.node.languages.LanguageRegistry
import org.helix.node.messages.MessageBundle
import org.helix.node.messages.MessageRegistry
import org.helix.node.gates.JoinGateRegistry
import org.helix.node.gates.NativePermissionCache
import org.helix.node.gates.NativePermissionProvider
import org.helix.node.gates.PermissionResolverRegistry
import org.helix.node.gates.PermissionService
import org.helix.node.gates.PlayerDataRegistry
import org.helix.node.gates.ProfileInfoRegistry
import org.helix.node.gates.ProfileSettingRegistry
import org.helix.node.identity.IdentityRegistry
import org.helix.node.privacy.AddressHashRegistry
import org.helix.node.privacy.PlayerDataActions
import org.helix.node.whitelist.WhitelistActions
import org.helix.node.whitelist.WhitelistStore
import org.helix.api.proxy.JoinDecision
import org.helix.node.notifications.NotificationBus
import org.helix.node.packs.NetworkPackService
import org.helix.api.platform.MetricSample
import org.helix.api.service.ServiceState
import org.helix.node.platform.ApiMetrics
import org.helix.node.platform.HeartbeatWatchdog
import org.helix.node.platform.MetricsHistory
import org.helix.node.platform.NodeHealthService
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.scheduler.JobScheduler
import org.helix.node.players.PlayerRegistry
import org.helix.node.proxy.ProxyCommandQueue
import org.helix.node.proxy.ProxyEventHub
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.resources.ClasspathInternalResources
import org.helix.node.scaling.AutoScaler
import org.helix.node.services.AdoptedProcessHandle
import org.helix.node.services.ForwardingSecret
import org.helix.node.services.ManagedService
import org.helix.node.services.ProcessIdentity
import org.helix.node.services.ProcessServiceExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.ServiceRegistryFile
import org.helix.node.services.WorkspacePreparer
import org.helix.node.services.docker.DockerNames
import org.helix.node.services.docker.DockerServiceExecutor
import org.helix.node.services.docker.DockerServiceHandle
import org.helix.node.services.docker.SystemCommandRunner
import org.helix.node.storage.StorageBackend
import org.helix.node.storage.StorageProvider
import org.helix.node.tasks.TaskStore
import org.helix.node.versions.ServerJarProvider
import org.helix.node.versions.VersionCatalog
import org.slf4j.LoggerFactory

/**
 * Wires and runs the complete node: task store, service manager, control
 * API, auto-scaler, addons and CLI.
 *
 * @property dataDirectory the `Helix/` data directory root.
 * @property shutdownWaitMillis total budget services get to stop gracefully
 *   during SIGTERM/shutdown before the process moves on regardless; kept
 *   comfortably above the wrapper's own 30s stop grace (and, for Docker, its
 *   30s `docker stop` timeout) so a slow world-save is not cut off mid-write.
 */
class HelixNode(
    private val dataDirectory: Path,
    private val logBuffer: LogBuffer = LogBuffer(),
    private val shutdownWaitMillis: Long = DEFAULT_SHUTDOWN_WAIT_MILLIS,
) {
    private val logger = LoggerFactory.getLogger(HelixNode::class.java)
    private val stopping = AtomicBoolean(false)

    /** Set during a backend restart so the shutdown path spares the services. */
    @Volatile
    private var keepServicesOnExit = false

    /** Recent node events for the dashboard timeline. */
    val eventLog: EventLog = EventLog()

    /** Data directory layout. */
    val paths: NodePaths = NodePaths(dataDirectory).createAll()

    /** Effective node configuration. */
    val config: NodeConfig = NodeConfigLoader().load(dataDirectory)

    /**
     * The selected storage backend (file, PostgreSQL or MongoDB). Owns the
     * shared database resource used by both addon storage and the audit log.
     */
    private val storageBackend: StorageBackend =
        StorageBackend.create(config.storage, paths.root.resolve("audit/audit.jsonl"))

    /** Complete, durable audit trail (file, PostgreSQL or MongoDB). */
    val audit: AuditLog = AuditLog(storageBackend.auditSink, retentionDays = config.audit.retentionDays)

    /** Configured tasks. */
    val taskStore: TaskStore = TaskStore(paths.tasks)

    /** Central action registry. */
    val registry: ActionRegistry = ActionRegistry()

    /** On-disk mirror of the running services, read back after a restart. */
    private val serviceRegistry = ServiceRegistryFile(paths.root.resolve("services/registry.json"))

    /** Single HTTP fetcher for server-jar downloads, reused across every service start. */
    private val serverJarFetcher = org.helix.node.versions.JavaHttpFetcher()

    /** Service lifecycle owner. */
    val manager: ServiceManager = ServiceManager(
        taskStore = taskStore,
        workspacePreparer = WorkspacePreparer(
            paths = paths,
            internalResources = ClasspathInternalResources(),
            serverJar = { environment, version ->
                // Re-read the catalog each start (URL overrides may have changed) but reuse the
                // one fetcher — a fresh JDK HttpClient per start leaked its executor threads.
                ServerJarProvider(paths.cache, VersionCatalog.load(dataDirectory), fetcher = serverJarFetcher)
                    .ensureJar(environment, version)
            },
            paperComponents = { taskName -> addonManager.paperComponents(taskName) },
            velocityComponents = { taskName -> addonManager.velocityComponents(taskName) },
            eulaAccepted = config.eula.accept,
            forwardingSecret = ForwardingSecret.resolve(
                config.proxy.forwardingSecret,
                paths.config.resolve("forwarding.secret"),
            ),
            legacyForwarding = config.proxy.legacyForwarding,
        ),
        executors = mapOf(
            ExecutorType.PROCESS to ProcessServiceExecutor(),
            ExecutorType.DOCKER to DockerServiceExecutor(config.docker),
        ),
        environmentProvider = ::bridgeEnvironment,
        eventSink = ::recordEvent,
        registry = serviceRegistry,
    )

    /** Proxy routing state. */
    val routing: ProxyRoutingService = ProxyRoutingService(manager)

    /** Per-service bridge tokens minted for managed services (see [bridgeEnvironment]). */
    private val serviceTokens: ServiceTokenRegistry = ServiceTokenRegistry()

    /** Wakes long-polling proxy bridges the instant something changes. */
    val proxyEvents: ProxyEventHub = ProxyEventHub()

    /** Aggregated join gates of all addons. */
    val joinGates: JoinGateRegistry = JoinGateRegistry()

    /** Operator-configurable network whitelist, independent of addons. */
    val whitelist: WhitelistStore = WhitelistStore(paths.config.resolve("whitelist.json")).also { store ->
        joinGates.register("core.whitelist") { request ->
            if (!store.isEnabled() || store.contains(request.name)) {
                JoinDecision.allow()
            } else {
                JoinDecision.deny("You are not whitelisted on this network.")
            }
        }
    }

    /** Pending commands for proxy bridges. */
    val commandQueue: ProxyCommandQueue = ProxyCommandQueue()

    /** Aggregated permission resolvers of all addons. */
    val permissionResolvers: PermissionResolverRegistry = PermissionResolverRegistry()

    /** Aggregated GDPR export/delete providers of all addons. */
    val playerData: PlayerDataRegistry = PlayerDataRegistry()

    /** Aggregated read-only profile-info providers of all addons. */
    val profileInfo: ProfileInfoRegistry = ProfileInfoRegistry()

    /** Aggregated interactive profile-setting providers of all addons. */
    val profileSettings: ProfileSettingRegistry = ProfileSettingRegistry()

    /** Per-player Minecraft-native permission snapshots reported by bridges. */
    val nativePermissions: NativePermissionCache = NativePermissionCache()

    /** Node-wide permission decisions: addon resolvers override the native default. */
    val permissionService: PermissionService =
        PermissionService(permissionResolvers, NativePermissionProvider(nativePermissions))

    /** Online players and player event fan-out. */
    val playerRegistry: PlayerRegistry = PlayerRegistry()

    /** Aggregated display resolvers of all addons. */
    val displayResolvers: DisplayResolverRegistry = DisplayResolverRegistry()

    /** Global values bridges poll, published by addons. */
    val bridgeValues: BridgeValueStore = BridgeValueStore()

    /** Notification bus between addons. */
    val notifications: NotificationBus = NotificationBus()

    /** Dashboard pages contributed by addons. */
    val dashboardPanels: DashboardPanelRegistry = DashboardPanelRegistry()

    /** Configurable message bundles of addons. */
    val messages: MessageRegistry = MessageRegistry()

    /** Backend for addon document storage (files or PostgreSQL). */
    val storageProvider: StorageProvider = storageBackend.storageProvider

    /** Network languages and per-player language preferences. */
    val languages: LanguageRegistry = LanguageRegistry(
        storageProvider.forAddon("translations", paths.root.resolve("translations")),
    )

    /** Node-wide uuid to last-known-name identity registry. */
    val identityRegistry: IdentityRegistry = IdentityRegistry(
        storageProvider.forAddon("identity", paths.root.resolve("identity")),
    )

    /** Salted join-address hashes backing the staff alt-account lookup. */
    val addressHashes: AddressHashRegistry = AddressHashRegistry(
        storageProvider.forAddon("addresses", paths.root.resolve("addresses")),
    )

    /**
     * Proxy-level texts rendered by the Velocity bridge: disconnect screens,
     * proxy command messages and generic fallbacks. Registered as the
     * `velocity` bundle, so the keys appear on the dashboard as
     * `helix.translations.velocity.*`.
     */
    val velocityMessages: MessageBundle = bundle(
        "velocity",
        mapOf(
            "en" to linkedMapOf(
                "screen.maintenance" to "<red><bold>Maintenance</bold>\n" +
                    "<gray>{network} is currently under maintenance.\n" +
                    "<gray>Please check back soon.",
                "screen.server_full" to "<red><bold>Network Full</bold>\n" +
                    "<gray>{network} is full right now <dark_gray>(<white>{online}<gray>/<white>{max}<dark_gray>)\n" +
                    "<gray>Please try again in a moment.",
                "command.lobby.none" to "{prefix} <red>No lobby available.",
                "command.server.usage" to "{prefix} <gray>Usage: <white>/server \\<name>",
                "command.server.unknown" to "{prefix} <red>Unknown server: <white>{server}",
                "command.servers.none" to "{prefix} <gray>No servers registered.",
                "command.servers.header" to "{prefix} <gray>Servers <dark_gray>({count})<gray>:",
                "command.servers.entry" to "<dark_gray> • <click:run_command:'/server {server}'>" +
                    "<hover:show_text:'<gray>Connect to <white>{server}'><aqua>{server}</aqua></hover></click>",
                "command.sent" to "{prefix} <gray>Sent to <white>{server}<gray>.",
                "command.unavailable" to "{prefix} <red>Command is currently unavailable.",
                "command.result.done" to "{prefix} <green>Done.",
                "command.result.failed" to "{prefix} <red>Failed.",
                "kick.default" to "{prefix} <red>You were kicked from the network.",
                "join.denied" to "<red>You may not join this network.",
                "motd.maintenance" to "<red>Maintenance",
            ),
            "de" to linkedMapOf(
                "screen.maintenance" to "<red><bold>Wartungsarbeiten</bold>\n" +
                    "<gray>{network} befindet sich gerade in Wartung.\n" +
                    "<gray>Schau später noch einmal vorbei.",
                "screen.server_full" to "<red><bold>Netzwerk voll</bold>\n" +
                    "<gray>{network} ist gerade voll <dark_gray>(<white>{online}<gray>/<white>{max}<dark_gray>)\n" +
                    "<gray>Bitte versuche es gleich noch einmal.",
                "command.lobby.none" to "{prefix} <red>Keine Lobby verfügbar.",
                "command.server.usage" to "{prefix} <gray>Benutzung: <white>/server \\<name>",
                "command.server.unknown" to "{prefix} <red>Unbekannter Server: <white>{server}",
                "command.servers.none" to "{prefix} <gray>Keine Server registriert.",
                "command.servers.header" to "{prefix} <gray>Server <dark_gray>({count})<gray>:",
                "command.servers.entry" to "<dark_gray> • <click:run_command:'/server {server}'>" +
                    "<hover:show_text:'<gray>Verbinde zu <white>{server}'><aqua>{server}</aqua></hover></click>",
                "command.sent" to "{prefix} <gray>Weitergeleitet zu <white>{server}<gray>.",
                "command.unavailable" to "{prefix} <red>Der Befehl ist gerade nicht verfügbar.",
                "command.result.done" to "{prefix} <green>Erledigt.",
                "command.result.failed" to "{prefix} <red>Fehlgeschlagen.",
                "kick.default" to "{prefix} <red>Du wurdest vom Netzwerk geworfen.",
                "join.denied" to "<red>Du darfst dieses Netzwerk nicht betreten.",
                "motd.maintenance" to "<red>Wartungsarbeiten",
            ),
        ),
    )

    /** Texts of the built-in `/helix` player command. */
    val helixMessages: MessageBundle = bundle(
        "helix",
        mapOf(
            "en" to linkedMapOf(
                "usage" to "{prefix} <gray>Usage: <white>/helix " +
                    "\\<language|addons|enable|disable|reload|backend restart|launcher restart>",
                "no_permission" to "{prefix} <red>You do not have permission to do that.",
                "restart.backend" to "{prefix} <green>Backend restart initiated — services keep running.",
                "restart.launcher" to "{prefix} <green>Launcher restart initiated — services stop and " +
                    "a fresh launcher starts.",
                "restart.unavailable" to "{prefix} <red>Restart unavailable (not running from Launcher.jar).",
                "language.current" to "{prefix} <gray>Your language: <white>{language}<gray>. " +
                    "Available: <white>{languages}<gray>. Change it with <white>/helix language \\<code><gray>.",
                "language.set" to "{prefix} <green>Language changed to <white>{language}<green>.",
                "language.unknown" to "{prefix} <red>Unknown language <white>{language}<red>. " +
                    "Available: <white>{languages}",
            ),
            "de" to linkedMapOf(
                "usage" to "{prefix} <gray>Benutzung: <white>/helix " +
                    "\\<language|addons|enable|disable|reload|backend restart|launcher restart>",
                "no_permission" to "{prefix} <red>Dafür hast du keine Berechtigung.",
                "restart.backend" to "{prefix} <green>Backend-Neustart gestartet — die Services laufen weiter.",
                "restart.launcher" to "{prefix} <green>Launcher-Neustart gestartet — Services stoppen und " +
                    "ein frischer Launcher startet.",
                "restart.unavailable" to "{prefix} <red>Neustart nicht verfügbar (läuft nicht aus einer Launcher.jar).",
                "language.current" to "{prefix} <gray>Deine Sprache: <white>{language}<gray>. " +
                    "Verfügbar: <white>{languages}<gray>. Ändern mit <white>/helix language \\<code><gray>.",
                "language.set" to "{prefix} <green>Sprache geändert zu <white>{language}<green>.",
                "language.unknown" to "{prefix} <red>Unbekannte Sprache <white>{language}<red>. " +
                    "Verfügbar: <white>{languages}",
            ),
        ),
    )

    /**
     * Free-form translations created on the dashboard, under
     * `helix.translations.custom.*`.
     */
    val customMessages: MessageBundle = bundle("custom", emptyMap())

    /**
     * Panel-editable network-wide texts, currently the global `{prefix}`
     * placeholder usable in every message everywhere.
     */
    val networkMessages: MessageBundle = bundle(
        "network",
        mapOf(
            // The restart.* templates keep a literal {prefix}: they are rendered by the
            // VELOCITY BRIDGE from the raw translation tables (flat key + ctx substitution),
            // never by MessageBundle.formatFor — the automatic chat prefix does not apply
            // on that path, the bridge fills {prefix} itself.
            "en" to linkedMapOf(
                "prefix" to "<gradient:#8b5cf6:#38bdf8><bold>Helix</bold></gradient> <dark_gray>»</dark_gray>",
                "name" to config.network.name,
                "restart.warn" to "{prefix} <gray><white>{target}</white> restarts in <white>{seconds}s</white>.",
                "restart.now" to "{prefix} <gray><white>{target}</white> is restarting now.",
                "restart.backend.start" to "{prefix} <gray>The backend is restarting — you may experience brief lag.",
                "restart.backend.done" to "{prefix} <green>Backend restart complete.",
            ),
            "de" to linkedMapOf(
                "restart.warn" to "{prefix} <gray><white>{target}</white> startet in <white>{seconds}s</white> neu.",
                "restart.now" to "{prefix} <gray><white>{target}</white> startet jetzt neu.",
                "restart.backend.start" to "{prefix} <gray>Die Backend-Server starten neu — " +
                    "es kann kurzzeitig zu Lags kommen.",
                "restart.backend.done" to "{prefix} <green>Backend-Restart beendet.",
            ),
        ),
    )

    /**
     * Creates and registers a language-aware message bundle of an owner.
     *
     * @param owner bundle owner id (`velocity`, `network`, …).
     * @param defaults default templates: language code to (key to template).
     * @return the registered bundle.
     */
    private fun bundle(owner: String, defaults: Map<String, Map<String, String>>): MessageBundle =
        MessageBundle(
            storage = storageProvider.forAddon(owner, paths.root.resolve(owner)),
            defaults = defaults,
            defaultLanguage = languages::defaultLanguage,
            languageOf = languages::languageOf,
        ).also { messages.register(owner, it) }

    /**
     * Re-reads the network texts, refreshes the global placeholders and
     * republishes them to the bridges.
     */
    fun refreshNetworkPlaceholders() {
        val prefix = networkMessages.raw("prefix")
        val name = networkMessages.raw("name")
        GlobalPlaceholders.set("prefix", prefix)
        GlobalPlaceholders.set("network", name)
        bridgeValues.publish("network", "network.prefix", prefix)
        bridgeValues.publish("network", "network.name", name)
    }

    /** Merged network resource pack of all enabled addons. */
    val networkPack: NetworkPackService = NetworkPackService(paths.root.resolve("packs"))

    /**
     * Rebuilds the merged network pack from all enabled addons and
     * republishes its SHA-1 (and the optional public URL override) as
     * bridge values, so proxies notice the change on their next sync.
     */
    fun rebuildNetworkPack() {
        networkPack.rebuild(addonManager.resourcePacks())
        bridgeValues.publish("network", "network.pack.sha1", networkPack.sha1() ?: "")
        networkPack.publicUrl()?.let { bridgeValues.publish("network", "network.pack_url", it) }
    }

    /**
     * Persists the operator-configured public pack download URL and
     * republishes it to the bridges; `null` resets to automatic resolution.
     *
     * @param url the client-reachable URL, or `null` to reset.
     */
    fun setNetworkPackUrl(url: String?) {
        networkPack.setPublicUrl(url)
        bridgeValues.publish("network", "network.pack_url", url ?: "")
    }

    /** Installed addons. */
    val addonManager: AddonManager = AddonManager(
        paths.addons,
        registry,
        joinGates,
        permissionResolvers,
        playerData,
        profileInfo,
        profileSettings,
        permissionService,
        playerRegistry,
        displayResolvers,
        bridgeValues,
        notifications,
        dashboardPanels,
        messages,
        storageProvider,
        taskAddonActive = { taskName, addonId ->
            taskStore.find(taskName)?.isAddonActive(addonId) ?: true
        },
        corePermissions = {
            buildList {
                add(config.control.loginPermission)
                addAll(PanelAuthService.VIEW_NODES.values)
                dashboardPanels.list().forEach { add(PanelAuthService.panelNode(it.id)) }
                add("helix.maintenance.bypass")
                add("helix.admin")
                registry.descriptors().filter { it.playerCommand }.mapNotNull { it.permission }.forEach(::add)
            }.distinct()
        },
        serviceDirectories = { listOf(paths.servicesStatic, paths.servicesTemp, paths.templates) },
        defaultLanguage = languages::defaultLanguage,
        languageOf = languages::languageOf,
        identityRegistry = identityRegistry,
        sharedAddressPlayers = addressHashes::sharing,
        storageConnection = {
            org.helix.api.addon.StorageConnection(
                mode = config.storage.mode,
                url = config.storage.url,
                user = config.storage.user,
                password = config.storage.password,
                database = config.storage.database,
                poolSize = config.storage.poolSize,
            )
        },
        onChange = { rebuildNetworkPack() },
    )

    private val overviewService = PlatformOverviewService(version(), taskStore, manager)

    /** Bounded history of network metrics for the dashboard graphs. */
    val metrics: MetricsHistory = MetricsHistory()

    /** Rolling control-API performance stats (avg/p95 response time, rate). */
    val apiMetrics: ApiMetrics = ApiMetrics()

    /** Workspace backups of static services, plus addon-data backups in `json` storage mode. */
    val backups: BackupService = BackupService(
        backupsDir = paths.backups,
        staticServicesDir = paths.servicesStatic,
        isActive = { serviceId -> manager.find(serviceId)?.active() == true },
        dataSources = jsonModeDataSources(),
    )

    /**
     * The `json`-mode data directories worth snapshotting alongside static
     * workspaces — empty for `postgres`/`mongodb`, where the same documents
     * live in the shared database instead (see [BackupService.createData]).
     */
    private fun jsonModeDataSources(): Map<String, Path> {
        if (config.storage.isPostgres() || config.storage.isMongo()) {
            return emptyMap()
        }
        return mapOf(
            "addons" to paths.addons.resolve("data"),
            "tasks" to paths.tasks,
            "translations" to paths.root.resolve("translations"),
            "audit" to paths.root.resolve("audit"),
        )
    }

    /** File manager over service workspaces and templates. */
    val files: FileManagerService =
        FileManagerService(paths.servicesStatic, paths.servicesTemp, paths.templates)

    /** Recurring scheduled jobs (announcements, maintenance toggles, …). */
    val jobScheduler: JobScheduler = JobScheduler(
        storage = storageProvider.forAddon("scheduler", paths.root.resolve("scheduler")),
        actions = registry,
        eventSink = ::recordEvent,
    )

    /** The node's own runtime health (process CPU/heap, host load, aggregates). */
    val nodeHealth: NodeHealthService =
        NodeHealthService(manager, playerRegistry, nativePermissions, jobScheduler)
    private val autoScaler = AutoScaler(taskStore, manager)

    /** Reaps services stuck in `STARTING` or gone silent while `RUNNING` (see [HeartbeatWatchdog]). */
    private val heartbeatWatchdog = HeartbeatWatchdog(manager)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "helix-autoscaler").apply { isDaemon = true }
    }

    /**
     * Own executor for scheduled-job evaluation, separate from [scheduler]:
     * without this, a slow job (e.g. a backup) run on the same thread as the
     * auto-scaler/metrics ticks would stall scaling and metrics sampling for
     * its whole duration.
     */
    private val jobSchedulerExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "helix-job-scheduler").apply { isDaemon = true }
    }
    /** Control API dependencies, kept as its own property so the launcher can
     *  reach [org.helix.node.control.auth.PanelAuthService] for the
     *  `session.revoke` admin action without exposing it via [ControlServer]. */
    private val controlDependencies = ControlDependencies(
        token = config.control.token,
        registry = registry,
        taskStore = taskStore,
        manager = manager,
        routing = routing,
        overviewService = overviewService,
        addonManager = addonManager,
        joinGates = joinGates,
        whitelist = whitelist,
        playerData = playerData,
        addressHashes = addressHashes,
        commandQueue = commandQueue,
        permissionResolvers = permissionResolvers,
        nativePermissions = nativePermissions,
        permissionService = permissionService,
        playerRegistry = playerRegistry,
        displayResolvers = displayResolvers,
        bridgeValues = bridgeValues,
        logBuffer = logBuffer,
        eventLog = eventLog,
        dashboardPanels = dashboardPanels,
        messages = messages,
        proxyEvents = proxyEvents,
        audit = audit,
        loginPermission = config.control.loginPermission,
        codeTtlSeconds = config.control.codeTtlSeconds,
        sessionTtlSeconds = config.control.sessionTtlSeconds,
        idleTimeoutSeconds = config.control.idleTimeoutSeconds,
        loginMessage = config.control.loginMessage,
        networkName = { networkMessages.raw("name") },
        proxyScreens = velocityMessages,
        languages = languages,
        identityRegistry = identityRegistry,
        metrics = metrics,
        apiMetrics = apiMetrics,
        nodeHealth = nodeHealth::snapshot,
        onMessagesChanged = { addonId ->
            if (addonId == "network") {
                refreshNetworkPlaceholders()
            }
            // screens and the display name ride on the routing snapshot,
            // everything else on the periodic bridge sync — wake proxies
            proxyEvents.bumpRouting()
        },
        jobScheduler = jobScheduler,
        backups = backups,
        files = files,
        networkPack = networkPack,
        serviceTokens = serviceTokens,
    )

    private val controlServer = ControlServer(settings = config.control, dependencies = controlDependencies)

    /**
     * Boots the node: loads tasks and addons, registers actions, starts the
     * control API and the auto-scaler.
     */
    fun start() {
        logger.info("Booting Helix-Cloud {} from {}", version(), dataDirectory.toAbsolutePath())
        if (config.control.token == DEFAULT_TOKEN_PLACEHOLDER) {
            // Only reachable via a hand-edited/legacy node.toml — fresh installs get a
            // random token from HelixDirectoryInitializer. Warn loudly instead of
            // silently running with a publicly known admin credential.
            logger.warn(
                "control.token is still the well-known default \"{}\" — replace it with a " +
                    "random secret, this token grants full admin access to the control API",
                DEFAULT_TOKEN_PLACEHOLDER,
            )
        }
        if (config.eula.accept) {
            val by = config.eula.acceptedBy.takeIf { it.isNotBlank() }
            logger.info("Mojang EULA (https://www.minecraft.net/eula) accepted{}", by?.let { " by $it" } ?: "")
            audit.record("node", "system", "Mojang EULA accepted" + (by?.let { " (by $it)" } ?: ""))
        } else {
            logger.warn(
                "eula.accept is false in config/node.toml — Paper services will refuse to start until " +
                    "you read and accept the Mojang EULA (https://www.minecraft.net/eula) and set accept = true",
            )
        }
        if (config.proxy.legacyForwarding) {
            logger.warn(
                "proxy.legacyForwarding is enabled — Paper/Velocity backends use unauthenticated " +
                    "BungeeCord-style forwarding instead of Velocity modern forwarding; anyone reaching a " +
                    "backend port directly can impersonate any player. Only use this if you understand the risk.",
            )
        }
        taskStore.reload()
        adoptSurvivingServices()
        sweepOrphanedWorkspaces()
        restoreRestartState()
        migrateLegacyProxyScreens()
        languages.onChange { proxyEvents.bumpRouting() }
        val addonActions = AddonActions(addonManager)
        BuiltinActions(
            paths = paths,
            taskStore = taskStore,
            manager = manager,
            routing = routing,
            overviewService = overviewService,
            versionCatalog = { VersionCatalog.load(dataDirectory) },
            shutdown = ::shutdown,
            commandQueue = commandQueue,
            playerRegistry = playerRegistry,
            eventSink = ::recordEvent,
            proxyEvents = proxyEvents,
            languages = languages,
            helixMessages = helixMessages,
            adminCheck = { player ->
                permissionService.check(
                    org.helix.api.proxy.PermissionCheckRequest(name = player, permission = "helix.admin"),
                )
            },
            addonSubcommands = addonActions::helixSubcommand,
            restartBackend = ::restartBackend,
            restartLauncher = ::restartLauncher,
            networkPackUrl = ::setNetworkPackUrl,
        ).registerAll(registry)
        addonActions.registerAll(registry)
        BackupActions(backups).registerAll(registry)
        registerControlActions()
        WhitelistActions(whitelist).registerAll(registry)
        PlayerDataActions(playerData).registerAll(registry)
        playerData.register(
            "node.addresses",
            /** The node's own address-hash store answers GDPR requests like any addon. */
            object : org.helix.api.addon.PlayerDataProvider {
                override fun export(player: String): String? =
                    identityRegistry.resolveUuid(player)?.let(addressHashes::export)

                override fun delete(player: String): Boolean =
                    identityRegistry.resolveUuid(player)?.let(addressHashes::delete) ?: false
            },
        )
        addonManager.loadAll()
        rebuildNetworkPack()
        registerEventSources()
        refreshNetworkPlaceholders()
        controlServer.start()
        manager.onServiceTerminated { service: ManagedService ->
            // A stopped service's token must not keep working once its id is
            // reused by a later service instance.
            serviceTokens.revoke(service.id)
            if (service.task.environment.proxy) {
                playerRegistry.dropProxy(service.id)
                // Drop the proxy's pending-command queue so a later service reusing this
                // id does not inherit the previous instance's undelivered commands.
                commandQueue.drop(service.id)
            }
            autoScaler.noteTermination(service)
            if (!stopping.get()) {
                logger.info("Service {} terminated, rebalancing", service.id)
                scheduler.execute(autoScaler::tick)
            }
        }
        scheduler.scheduleAtFixedRate(
            { runCatching(autoScaler::tick).onFailure { logger.error("scaler tick failed", it) } },
            SCALER_INITIAL_DELAY_SECONDS,
            SCALER_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        scheduler.scheduleAtFixedRate(
            { runCatching(heartbeatWatchdog::tick).onFailure { logger.error("heartbeat watchdog tick failed", it) } },
            SCALER_INITIAL_DELAY_SECONDS,
            SCALER_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        scheduler.scheduleAtFixedRate(
            { runCatching(::sampleMetrics).onFailure { logger.error("metrics sample failed", it) } },
            METRICS_PERIOD_SECONDS,
            METRICS_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        // Own executor (see jobSchedulerExecutor's doc) — a slow job must
        // not delay the auto-scaler/metrics ticks above, nor vice versa.
        jobSchedulerExecutor.scheduleAtFixedRate(
            { runCatching(jobScheduler::tick).onFailure { logger.error("job scheduler tick failed", it) } },
            JOB_PERIOD_SECONDS,
            JOB_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        scheduler.scheduleAtFixedRate(
            { runCatching(audit::pruneExpired).onFailure { logger.error("audit prune failed", it) } },
            AUDIT_PRUNE_PERIOD_SECONDS,
            AUDIT_PRUNE_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        Runtime.getRuntime().addShutdownHook(
            Thread {
                if (!keepServicesOnExit) {
                    // Mark the node as stopping and silence the auto-scaler,
                    // otherwise it resurrects services while the hook stops them.
                    stopping.set(true)
                    scheduler.shutdownNow()
                    jobSchedulerExecutor.shutdownNow()
                    stopServicesQuietly()
                    // Reached on a raw SIGTERM (systemd, `kill`) that never went
                    // through shutdown()/initiateRestart() — those already close
                    // the storage backend themselves, this is the only path that
                    // otherwise leaked the connection pool/client on exit.
                    runCatching { audit.close() }
                    runCatching { storageProvider.close() }
                    runCatching { storageBackend.close() }
                }
            },
        )
        if (completedBackendRestart) {
            // The proxies survived headless and re-poll within a second —
            // the queued broadcast reaches them on their next drain.
            broadcastToProxies(
                "helix.translations.network.restart.backend.done",
                "{prefix} <green>Backend restart complete.",
            )
            eventLog.record("node", "Backend restart completed", "info")
        }
        // The admin token is never logged: GET /logs only requires the (non-admin)
        // helix.panel.logs permission, so logging it would leak full admin access
        // to anyone holding that view.
        logger.info(
            "Node ready — dashboard: http://{}:{}/",
            config.control.host,
            config.control.port,
        )
    }

    /**
     * Runs the interactive CLI until the input ends or `platform.stop` runs.
     *
     * A respawned node (`HELIX_RELAUNCH=1`) or a service-managed node
     * (systemd) usually has no terminal on stdin; instead of shutting down
     * on the immediate EOF it stays alive until `platform.stop`, a restart
     * or the service manager terminates the process.
     */
    fun runCli() {
        NodeCli(registry).run(System.`in`.bufferedReader())
        if (stopping.get()) {
            return
        }
        if (System.getenv("HELIX_RELAUNCH") == "1" || serviceManaged()) {
            logger.info("Console input ended — node keeps running (stop via panel or platform.stop)")
            java.util.concurrent.CountDownLatch(1).await()
        } else {
            shutdown()
        }
    }

    /**
     * Stops services, addons and the control API, then exits the process.
     */
    fun shutdown() {
        if (!stopping.compareAndSet(false, true)) {
            return
        }
        Thread({
            logger.info("Shutting down")
            scheduler.shutdownNow()
            jobSchedulerExecutor.shutdownNow()
            stopServicesQuietly()
            addonManager.disableAll()
            controlServer.stop()
            runCatching { audit.close() }
            runCatching { storageProvider.close() }
            runCatching { storageBackend.close() }
            exitProcess(0)
        }, "helix-shutdown").start()
    }

    /**
     * Restarts the node process while every running service keeps running
     * headless; the successor process re-adopts them.
     *
     * The runtime state (maintenance flag, player roster, permission
     * snapshots, scheduler timing, metrics history) is written to disk and
     * restored by the successor. Standalone nodes spawn a fresh launcher
     * process themselves; under a service manager (systemd) the node exits
     * with [RESTART_EXIT_CODE] and the unit's `Restart=on-failure` brings
     * the successor up.
     *
     * @return `true` when the restart was initiated; `false` when not
     *   running from a `Launcher.jar` or already stopping.
     */
    fun restartBackend(): Boolean = initiateRestart(keepServices = true)

    /**
     * Stops every service gracefully, then replaces this process with a
     * freshly started `Launcher.jar` (picking up a replaced jar file).
     *
     * @return `true` when the restart was initiated.
     */
    fun restartLauncher(): Boolean = initiateRestart(keepServices = false)

    private fun initiateRestart(keepServices: Boolean): Boolean {
        val managed = serviceManaged()
        val jar = if (managed) null else LauncherRespawn.launcherJar()
        if (!managed && jar == null) {
            logger.error("Restart unavailable — not running from a Launcher.jar")
            return false
        }
        if (!stopping.compareAndSet(false, true)) {
            return false
        }
        keepServicesOnExit = keepServices
        val what = if (keepServices) "Backend" else "Launcher"
        val suffix = if (keepServices) " — services keep running" else ""
        eventLog.record("node", "$what restart initiated$suffix", "warn")
        audit.record("node", "system", "$what restart initiated")
        Thread({
            logger.info("{} restart: respawning via {}", what, if (managed) "the service manager" else jar)
            if (keepServices) {
                // Announce the restart and give the proxies' long-poll a
                // moment to deliver it before the control API goes down.
                if (broadcastToProxies(
                        "helix.translations.network.restart.backend.start",
                        "{prefix} <gray>The backend is restarting — you may experience brief lag.",
                    )
                ) {
                    runCatching { Thread.sleep(BROADCAST_GRACE_MILLIS) }
                }
                runCatching(::saveRestartState)
                    .onFailure { logger.warn("Could not persist the restart state: {}", it.message) }
            }
            scheduler.shutdownNow()
            jobSchedulerExecutor.shutdownNow()
            if (!keepServices) {
                stopServicesQuietly()
            }
            addonManager.disableAll()
            controlServer.stop()
            runCatching { audit.close() }
            runCatching { storageProvider.close() }
            runCatching { storageBackend.close() }
            val exitCode = when {
                managed -> RESTART_EXIT_CODE
                LauncherRespawn.spawn(requireNotNull(jar)) -> 0
                else -> {
                    logger.error("Failed to spawn the successor launcher — start it manually")
                    1
                }
            }
            exitProcess(exitCode)
        }, "helix-restart").start()
        return true
    }

    /**
     * Whether a service manager (systemd) supervises this process, either
     * declared via `HELIX_SYSTEMD=1` or detected through systemd's
     * `INVOCATION_ID`. Restarts then delegate the respawn to the manager.
     */
    private fun serviceManaged(): Boolean =
        System.getenv("HELIX_SYSTEMD") == "1" || System.getenv("INVOCATION_ID") != null

    /**
     * Re-adopts services that survived a backend restart headless: process
     * services via their persisted pid — confirmed by [ProcessIdentity]
     * against the persisted OS start instant, so a pid reused by an
     * unrelated process after a reboot is not mistaken for the original —
     * Docker services via their deterministic container name. Entries whose
     * process or container died (or whose identity does not match) are
     * dropped; the auto-scaler starts replacements.
     */
    private fun adoptSurvivingServices() {
        val persisted = serviceRegistry.read()
        if (persisted == null) {
            // Parse failure: the previous process may well have left
            // survivors behind that we simply cannot identify. Leave
            // registryServiceIds at null so the orphan sweep is skipped —
            // wiping every survivor's workspace would be far worse than a
            // missed cleanup.
            registryServiceIds = null
            logger.warn("Service registry is unreadable — skipping adoption and orphan-workspace sweep")
            return
        }
        registryServiceIds = persisted.map { it.id }.toSet()
        val entries = persisted.filter {
            it.state != ServiceState.STOPPED && it.state != ServiceState.FAILED
        }
        if (entries.isEmpty()) {
            return
        }
        var adopted = 0
        entries.forEach { entry ->
            val task = taskStore.find(entry.task)
            if (task == null) {
                logger.warn(
                    "Surviving service {} belongs to an unknown task {} — left running unmanaged",
                    entry.id,
                    entry.task,
                )
                return@forEach
            }
            val workspace = Path.of(entry.workspace)
            val handle = when (entry.executor) {
                ExecutorType.PROCESS -> entry.pid
                    ?.let { pid -> ProcessHandle.of(pid).orElse(null) }
                    ?.takeIf { ProcessIdentity.survived(it, entry.processStartInstantEpochMs) }
                    ?.let { AdoptedProcessHandle(it, workspace.resolve("service.log")) }
                ExecutorType.DOCKER -> DockerServiceHandle(
                    containerName = DockerNames.containerName(entry.id),
                    runner = SystemCommandRunner(),
                    workspace = workspace,
                ).takeIf { it.alive }
            }
            if (handle != null) {
                // The surviving process still authenticates with the token
                // from its original environment — accept exactly that token
                // again, or every /internal/ call would get 403 and the
                // heartbeat watchdog would kill the survivor.
                entry.controlToken?.let { serviceTokens.restore(entry.id, it) }
                manager.adopt(task, entry, handle)
                adopted++
            } else {
                logger.info("Service {} did not survive the restart", entry.id)
            }
        }
        logger.info("Adopted {}/{} surviving service(s)", adopted, entries.size)
        manager.flushRegistry()
    }

    /**
     * Ids listed in the service registry file as read at boot, before the
     * post-adoption rewrite dropped dead entries; `null` when the registry
     * existed but could not be parsed. Used to keep the orphan sweep away
     * from workspaces of services the previous process still knew about.
     */
    private var registryServiceIds: Set<String>? = emptySet()

    /**
     * Removes `services/temp` workspaces left behind by a service that never
     * made it into [adoptSurvivingServices] (crashed before this boot, and
     * its own [org.helix.node.services.ServiceManager] cleanup never ran).
     * Must run after [adoptSurvivingServices] so genuinely-adopted workspaces
     * are never mistaken for orphans. Every id named in the boot-time
     * registry file is spared as well — a survivor that could not be adopted
     * (unknown task, unmatched pid) may still be running, and deleting the
     * workspace under a live server would corrupt it. When the registry file
     * was unreadable the sweep is skipped entirely.
     */
    private fun sweepOrphanedWorkspaces() {
        val persistedIds = registryServiceIds
        if (persistedIds == null) {
            logger.warn("Skipping orphan-workspace sweep — the service registry could not be parsed")
            return
        }
        val liveIds = manager.managedServices().map { it.id }.toSet() + persistedIds
        val removed = OrphanWorkspaceSweeper.sweep(paths.servicesTemp, liveIds)
        if (removed > 0) {
            logger.info("Removed {} orphaned service workspace(s)", removed)
        }
    }

    /** Writes the in-memory runtime state for the successor process. */
    private fun saveRestartState() {
        val state = RestartState(
            maintenance = routing.maintenance,
            players = playerRegistry.online(),
            nativePermissions = nativePermissions.snapshot(),
            jobLastRuns = jobScheduler.lastRuns(),
            metrics = metrics.recent(Int.MAX_VALUE),
        )
        java.nio.file.Files.writeString(paths.root.resolve(RESTART_STATE_FILE), Json.encodeToString(state))
    }

    /** Whether this boot completed a backend restart, for the done-broadcast. */
    @Volatile
    private var completedBackendRestart = false

    /**
     * Queues a translated broadcast on every active proxy and wakes their
     * long-polls.
     *
     * @param key translation key resolved per receiving player.
     * @param fallback template used while the key is not yet synced.
     * @return `true` when at least one proxy was notified.
     */
    private fun broadcastToProxies(key: String, fallback: String): Boolean {
        val proxies = manager.managedServices()
            .filter { it.task.environment.proxy && it.active() }
            .map { it.id }
        if (proxies.isEmpty()) {
            return false
        }
        commandQueue.enqueue(proxies, org.helix.api.proxy.ProxyCommand.broadcastKey(key, fallback))
        proxyEvents.signal()
        return true
    }

    /** Restores the runtime state left behind by the predecessor, once. */
    private fun restoreRestartState() {
        val file = paths.root.resolve(RESTART_STATE_FILE)
        if (!java.nio.file.Files.exists(file)) {
            return
        }
        completedBackendRestart = true
        runCatching {
            val state = Json { ignoreUnknownKeys = true }
                .decodeFromString<RestartState>(java.nio.file.Files.readString(file))
            routing.maintenance = state.maintenance
            playerRegistry.restore(state.players)
            nativePermissions.restore(state.nativePermissions)
            jobScheduler.restoreLastRuns(state.jobLastRuns)
            metrics.restore(state.metrics)
            logger.info(
                "Restored runtime state from before the restart ({} player(s), maintenance={})",
                state.players.size,
                state.maintenance,
            )
        }.onFailure { logger.warn("Could not restore the restart state: {}", it.message) }
        java.nio.file.Files.deleteIfExists(file)
    }

    /**
     * Imports edited disconnect screens of the pre-translation `proxy`
     * bundle into `helix.translations.velocity.screen.*`, once.
     *
     * The legacy document is only deleted after a fully successful import;
     * a failed import keeps it so the operator's edits are not lost and
     * the migration can retry on the next boot.
     */
    private fun migrateLegacyProxyScreens() {
        val legacy = storageProvider.forAddon("proxy", paths.root.resolve("proxy"))
        val raw = legacy.read("messages") ?: return
        runCatching {
            val doc = Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, String>>(raw)
            listOf("maintenance" to "screen.maintenance", "server_full" to "screen.server_full")
                .forEach { (legacyKey, key) ->
                    doc[legacyKey]?.takeIf { it != velocityMessages.rawIn("en", key) }
                        ?.let { velocityMessages.set("en", key, it) }
                }
        }.onSuccess {
            legacy.delete("messages")
            logger.info("Migrated legacy proxy screens into the translation system")
        }.onFailure { failure ->
            logger.warn(
                "Could not migrate legacy proxy screens — keeping the legacy document for the next attempt: {}",
                failure.message,
            )
        }
    }

    /**
     * Stops every active service and waits up to [shutdownWaitMillis] total
     * for them to actually terminate.
     *
     * Stop requests are issued in parallel, one thread per service: a
     * Docker-backed service's [org.helix.node.services.ServiceHandle.stop]
     * blocks for up to `docker stop`'s own 30s timeout, so issuing them one
     * after another (as a plain `forEach` would) could burn through the
     * entire shutdown budget on the first few containers alone, leaving none
     * for the rest — or for actually waiting on a graceful world-save.
     */
    private fun stopServicesQuietly() {
        val deadline = System.currentTimeMillis() + shutdownWaitMillis
        val toStop = manager.managedServices().filter { it.active() && it.handle != null }
        if (toStop.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(toStop.size) { runnable ->
                Thread(runnable, "helix-shutdown-stop").apply { isDaemon = true }
            }
            try {
                val tasks = toStop.map { service -> Callable { runCatching { manager.stopService(service.id) } } }
                val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                pool.invokeAll(tasks, remainingMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                pool.shutdownNow()
            }
        }
        while (System.currentTimeMillis() < deadline &&
            manager.managedServices().any { it.active() && it.handle != null }
        ) {
            Thread.sleep(100)
        }
    }

    /** Registers admin-only actions on the control API itself, not tied to
     *  any single addon or the built-in platform action set. */
    private fun registerControlActions() {
        registry.register(
            org.helix.api.action.ActionDescriptor(
                name = "session.revoke",
                description = "Revokes a player's active web-panel sessions.",
                usage = "session.revoke <player>",
            ),
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
            if (player.isNullOrBlank()) {
                org.helix.api.action.ActionResult.error("usage: session.revoke <player>")
            } else {
                val revoked = controlDependencies.panelAuth.revokeSessions(player)
                org.helix.api.action.ActionResult.ok("revoked $revoked session(s) for $player")
            }
        }
    }

    private fun registerEventSources() {
        playerRegistry.register(
            "__events__",
            /** Records player join/leave into the event log. */
            object : org.helix.api.addon.PlayerListener {
                override fun onJoin(player: org.helix.api.player.OnlinePlayer) {
                    eventLog.record("player", "${player.name} joined the network")
                    audit.record("player", player.name, "joined the network")
                }

                override fun onLeave(player: org.helix.api.player.OnlinePlayer) {
                    eventLog.record("player", "${player.name} left the network")
                    audit.record("player", player.name, "left the network")
                }
            },
        )
        notifications.register("__events__") { category, message ->
            val plain = message.replace(Regex("&[0-9a-fk-orA-FK-OR]"), "")
            eventLog.record(category, plain)
            audit.record(category, "addon", plain)
        }
        // Every action invocation is audited (CLI, REST, bridge, addon). A BRIDGE-sourced
        // invocation is always a player-issued in-game command (PlayerCommandService.execute
        // puts the player's name first, by contract) — attribute it to that player instead of
        // the generic "bridge" label, so the audit trail shows WHO ran a command, not just that
        // some proxy relayed one. A REST invocation carries the same real name when it came from
        // an authenticated panel session (set by ControlServer); the static admin token leaves it
        // unset and keeps the generic label.
        registry.onInvocation { invocation, result ->
            val actor = if (invocation.source == ActionSource.BRIDGE) {
                invocation.arguments.firstOrNull()?.lowercase()?.takeIf { it.isNotBlank() }
                    ?: invocation.source.name.lowercase()
            } else {
                invocation.actor?.lowercase()?.takeIf { it.isNotBlank() } ?: invocation.source.name.lowercase()
            }
            val summary = (invocation.action + " " + invocation.arguments.joinToString(" ")).trim()
            audit.record("action", actor, summary, if (result.success) "ok" else "error")
        }
        // Any change to registered actions may add/remove a player-command,
        // so wake long-polling proxies to re-register instantly.
        registry.onChange { proxyEvents.bumpCommandCatalog() }
        eventLog.record("node", "Node started (version ${version()})")
        audit.record("node", "system", "Node started (version ${version()})")
    }

    private fun recordEvent(category: String, level: String, message: String) {
        eventLog.record(category, message, level)
        audit.record(category, "system", message, if (level == "info") "ok" else level)
        // Service lifecycle and maintenance change the routing snapshot;
        // wake long-polling proxies so they re-fetch it instantly.
        if (category == "service" || category == "proxy") {
            proxyEvents.bumpRouting()
        }
    }

    private fun bridgeEnvironment(service: ManagedService): Map<String, String> {
        val host = when (service.task.executor) {
            ExecutorType.PROCESS -> "127.0.0.1"
            ExecutorType.DOCKER -> "host.docker.internal"
        }
        // A per-service token, not the static admin token: any plugin on the
        // managed game server can read its own process's environment, so
        // handing out the admin token here would let it act as full
        // node-admin (create tasks, read configs, stop the network). The
        // scoped token only unlocks this exact service's /internal/ routes.
        return mapOf(
            "HELIX_CONTROL_URL" to "http://$host:${config.control.port}",
            "HELIX_CONTROL_TOKEN" to serviceTokens.mint(service.id),
        )
    }

    private fun version(): String =
        HelixNode::class.java.`package`.implementationVersion ?: "dev"

    /** Records one network metric sample from the current live state. */
    private fun sampleMetrics() {
        val overview = overviewService.overview()
        val tpsValues = manager.managedServices()
            .filter { !it.task.environment.proxy && it.state == ServiceState.RUNNING }
            .mapNotNull { it.tps }
        val resources = nodeHealth.serviceResources()
        metrics.record(
            MetricSample(
                epochMs = System.currentTimeMillis(),
                onlinePlayers = overview.onlinePlayers,
                maxPlayers = overview.maxPlayers,
                servicesRunning = overview.servicesRunning,
                servicesTotal = overview.servicesTotal,
                avgTps = if (tpsValues.isEmpty()) null else tpsValues.average(),
                avgApiMs = apiMetrics.recentAverageMs(),
                nodeCpuPercent = org.helix.api.bridge.ResourceProbe.cpuPercent(),
                nodeHeapUsedMb = org.helix.api.bridge.ResourceProbe.memoryUsedMb(),
                nodeHeapMaxMb = org.helix.api.bridge.ResourceProbe.memoryMaxMb(),
                systemLoadAverage = Math.round(
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean().systemLoadAverage * 100.0,
                ) / 100.0,
                servicesCpuPercent = resources.cpuPercent,
                servicesMemoryUsedMb = resources.memoryUsedMb,
                servicesMemoryMaxMb = resources.memoryMaxMb,
            ),
        )
    }

    private companion object {
        /** The well-known default admin token, warned about if still in use. */
        const val DEFAULT_TOKEN_PLACEHOLDER = "dev-token-change-me"

        /** Seconds before the first auto-scaler pass. */
        const val SCALER_INITIAL_DELAY_SECONDS = 3L

        /** Seconds between auto-scaler passes. */
        const val SCALER_PERIOD_SECONDS = 5L

        /** Seconds between network metric samples. */
        const val METRICS_PERIOD_SECONDS = 15L

        /** Seconds between scheduled-job evaluations. */
        const val JOB_PERIOD_SECONDS = 20L

        /**
         * Default milliseconds to wait for services during shutdown —
         * comfortably above the wrapper's own 30s stop grace (and Docker's
         * 30s `docker stop` timeout), since services now stop in parallel
         * rather than serially.
         */
        const val DEFAULT_SHUTDOWN_WAIT_MILLIS = 40_000L

        /** Seconds between audit-retention sweeps. */
        const val AUDIT_PRUNE_PERIOD_SECONDS = 3_600L

        /** File name of the persisted runtime state during a restart. */
        const val RESTART_STATE_FILE = "restart-state.json"

        /**
         * Exit code signalling a service manager (systemd unit with
         * `Restart=on-failure`) to start the successor launcher.
         */
        const val RESTART_EXIT_CODE = 10

        /**
         * Milliseconds the proxies' long-poll gets to deliver the
         * restart-start broadcast before the control API goes down.
         */
        const val BROADCAST_GRACE_MILLIS = 1_500L
    }
}
