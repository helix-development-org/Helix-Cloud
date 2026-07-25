package org.helix.node.launcher

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
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
import org.helix.node.notifications.NotificationBus
import org.helix.api.platform.MetricSample
import org.helix.api.service.ServiceState
import org.helix.node.platform.ApiMetrics
import org.helix.node.platform.MetricsHistory
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.scheduler.JobScheduler
import org.helix.node.players.PlayerRegistry
import org.helix.node.proxy.ProxyCommandQueue
import org.helix.node.proxy.ProxyEventHub
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.resources.ClasspathInternalResources
import org.helix.node.scaling.AutoScaler
import org.helix.node.services.AdoptedProcessHandle
import org.helix.node.services.ManagedService
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
 */
class HelixNode(
    private val dataDirectory: Path,
    private val logBuffer: LogBuffer = LogBuffer(),
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
    val audit: AuditLog = AuditLog(storageBackend.auditSink)

    /** Configured tasks. */
    val taskStore: TaskStore = TaskStore(paths.tasks)

    /** Central action registry. */
    val registry: ActionRegistry = ActionRegistry()

    /** On-disk mirror of the running services, read back after a restart. */
    private val serviceRegistry = ServiceRegistryFile(paths.root.resolve("services/registry.json"))

    /** Service lifecycle owner. */
    val manager: ServiceManager = ServiceManager(
        taskStore = taskStore,
        workspacePreparer = WorkspacePreparer(
            paths = paths,
            internalResources = ClasspathInternalResources(),
            serverJar = { environment, version ->
                ServerJarProvider(paths.cache, VersionCatalog.load(dataDirectory))
                    .ensureJar(environment, version)
            },
            paperComponents = { taskName -> addonManager.paperComponents(taskName) },
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

    /** Wakes long-polling proxy bridges the instant something changes. */
    val proxyEvents: ProxyEventHub = ProxyEventHub()

    /** Aggregated join gates of all addons. */
    val joinGates: JoinGateRegistry = JoinGateRegistry()

    /** Pending commands for proxy bridges. */
    val commandQueue: ProxyCommandQueue = ProxyCommandQueue()

    /** Aggregated permission resolvers of all addons. */
    val permissionResolvers: PermissionResolverRegistry = PermissionResolverRegistry()

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

    /** Installed addons. */
    val addonManager: AddonManager = AddonManager(
        paths.addons,
        registry,
        joinGates,
        permissionResolvers,
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
    )

    private val overviewService = PlatformOverviewService(version(), taskStore, manager)

    /** Bounded history of network metrics for the dashboard graphs. */
    val metrics: MetricsHistory = MetricsHistory()

    /** Rolling control-API performance stats (avg/p95 response time, rate). */
    val apiMetrics: ApiMetrics = ApiMetrics()

    /** Workspace backups of static services. */
    val backups: BackupService = BackupService(
        backupsDir = paths.backups,
        staticServicesDir = paths.servicesStatic,
        isActive = { serviceId -> manager.find(serviceId)?.active() == true },
    )

    /** File manager over service workspaces and templates. */
    val files: FileManagerService =
        FileManagerService(paths.servicesStatic, paths.servicesTemp, paths.templates)

    /** Recurring scheduled jobs (announcements, maintenance toggles, …). */
    val jobScheduler: JobScheduler = JobScheduler(
        storage = storageProvider.forAddon("scheduler", paths.root.resolve("scheduler")),
        actions = registry,
        eventSink = ::recordEvent,
    )
    private val autoScaler = AutoScaler(taskStore, manager)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "helix-autoscaler").apply { isDaemon = true }
    }
    private val controlServer = ControlServer(
        settings = config.control,
        dependencies = ControlDependencies(
            token = config.control.token,
            registry = registry,
            taskStore = taskStore,
            manager = manager,
            routing = routing,
            overviewService = overviewService,
            addonManager = addonManager,
            joinGates = joinGates,
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
            loginMessage = config.control.loginMessage,
            networkName = { networkMessages.raw("name") },
            proxyScreens = velocityMessages,
            languages = languages,
            metrics = metrics,
            apiMetrics = apiMetrics,
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
        ),
    )

    /**
     * Boots the node: loads tasks and addons, registers actions, starts the
     * control API and the auto-scaler.
     */
    fun start() {
        logger.info("Booting Helix-Cloud {} from {}", version(), dataDirectory.toAbsolutePath())
        taskStore.reload()
        adoptSurvivingServices()
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
        ).registerAll(registry)
        addonActions.registerAll(registry)
        BackupActions(backups).registerAll(registry)
        addonManager.loadAll()
        registerEventSources()
        refreshNetworkPlaceholders()
        controlServer.start()
        manager.onServiceTerminated { service: ManagedService ->
            if (service.task.environment.proxy) {
                playerRegistry.dropProxy(service.id)
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
            { runCatching(::sampleMetrics).onFailure { logger.error("metrics sample failed", it) } },
            METRICS_PERIOD_SECONDS,
            METRICS_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        scheduler.scheduleAtFixedRate(
            { runCatching(jobScheduler::tick).onFailure { logger.error("job scheduler tick failed", it) } },
            JOB_PERIOD_SECONDS,
            JOB_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        Runtime.getRuntime().addShutdownHook(
            Thread {
                if (!keepServicesOnExit) {
                    // Mark the node as stopping and silence the auto-scaler,
                    // otherwise it resurrects services while the hook stops them.
                    stopping.set(true)
                    scheduler.shutdownNow()
                    stopServicesQuietly()
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
        logger.info(
            "Node ready — dashboard: http://{}:{}/  token: {}",
            config.control.host,
            config.control.port,
            config.control.token,
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
            stopServicesQuietly()
            addonManager.disableAll()
            controlServer.stop()
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
            if (!keepServices) {
                stopServicesQuietly()
            }
            addonManager.disableAll()
            controlServer.stop()
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
     * services via their persisted pid, Docker services via their
     * deterministic container name. Entries whose process or container died
     * are dropped; the auto-scaler starts replacements.
     */
    private fun adoptSurvivingServices() {
        val entries = serviceRegistry.read().filter {
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
                    ?.takeIf { it.isAlive }
                    ?.let { AdoptedProcessHandle(it, workspace.resolve("service.log")) }
                ExecutorType.DOCKER -> DockerServiceHandle(
                    containerName = DockerNames.containerName(entry.id),
                    runner = SystemCommandRunner(),
                    workspace = workspace,
                ).takeIf { it.alive }
            }
            if (handle != null) {
                manager.adopt(task, entry, handle)
                adopted++
            } else {
                logger.info("Service {} did not survive the restart", entry.id)
            }
        }
        logger.info("Adopted {}/{} surviving service(s)", adopted, entries.size)
        serviceRegistry.write(manager.managedServices())
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
        }
        legacy.delete("messages")
        logger.info("Migrated legacy proxy screens into the translation system")
    }

    private fun stopServicesQuietly() {
        runCatching { manager.stopAll() }
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MILLIS
        while (System.currentTimeMillis() < deadline &&
            manager.managedServices().any { it.active() && it.handle != null }
        ) {
            Thread.sleep(100)
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
        // Every action invocation is audited (CLI, REST, bridge, addon).
        registry.onInvocation { invocation, result ->
            val summary = (invocation.action + " " + invocation.arguments.joinToString(" ")).trim()
            audit.record("action", invocation.source.name.lowercase(), summary, if (result.success) "ok" else "error")
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
        return mapOf(
            "HELIX_CONTROL_URL" to "http://$host:${config.control.port}",
            "HELIX_CONTROL_TOKEN" to config.control.token,
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
        metrics.record(
            MetricSample(
                epochMs = System.currentTimeMillis(),
                onlinePlayers = overview.onlinePlayers,
                maxPlayers = overview.maxPlayers,
                servicesRunning = overview.servicesRunning,
                servicesTotal = overview.servicesTotal,
                avgTps = if (tpsValues.isEmpty()) null else tpsValues.average(),
                avgApiMs = apiMetrics.recentAverageMs(),
            ),
        )
    }

    private companion object {
        /** Seconds before the first auto-scaler pass. */
        const val SCALER_INITIAL_DELAY_SECONDS = 3L

        /** Seconds between auto-scaler passes. */
        const val SCALER_PERIOD_SECONDS = 5L

        /** Seconds between network metric samples. */
        const val METRICS_PERIOD_SECONDS = 15L

        /** Seconds between scheduled-job evaluations. */
        const val JOB_PERIOD_SECONDS = 20L

        /** Maximum milliseconds to wait for services during shutdown. */
        const val SHUTDOWN_WAIT_MILLIS = 15_000L

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
