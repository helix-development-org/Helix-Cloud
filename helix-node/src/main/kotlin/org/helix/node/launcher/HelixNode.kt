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
import org.helix.node.addons.AddonManager
import org.helix.node.cli.NodeCli
import org.helix.node.config.NodeConfig
import org.helix.node.config.NodeConfigLoader
import org.helix.node.control.ControlDependencies
import org.helix.node.control.ControlServer
import org.helix.node.gates.JoinGateRegistry
import org.helix.node.gates.PermissionResolverRegistry
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.proxy.ProxyCommandQueue
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.resources.ClasspathInternalResources
import org.helix.node.scaling.AutoScaler
import org.helix.node.services.ManagedService
import org.helix.node.services.ProcessServiceExecutor
import org.helix.node.services.ServiceManager
import org.helix.node.services.WorkspacePreparer
import org.helix.node.services.docker.DockerServiceExecutor
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
class HelixNode(private val dataDirectory: Path) {
    private val logger = LoggerFactory.getLogger(HelixNode::class.java)
    private val stopping = AtomicBoolean(false)

    /** Data directory layout. */
    val paths: NodePaths = NodePaths(dataDirectory).createAll()

    /** Effective node configuration. */
    val config: NodeConfig = NodeConfigLoader().load(dataDirectory)

    /** Configured tasks. */
    val taskStore: TaskStore = TaskStore(paths.tasks)

    /** Central action registry. */
    val registry: ActionRegistry = ActionRegistry()

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
        ),
        executors = mapOf(
            ExecutorType.PROCESS to ProcessServiceExecutor(),
            ExecutorType.DOCKER to DockerServiceExecutor(config.docker),
        ),
        environmentProvider = ::bridgeEnvironment,
    )

    /** Proxy routing state. */
    val routing: ProxyRoutingService = ProxyRoutingService(manager)

    /** Aggregated join gates of all addons. */
    val joinGates: JoinGateRegistry = JoinGateRegistry()

    /** Pending commands for proxy bridges. */
    val commandQueue: ProxyCommandQueue = ProxyCommandQueue()

    /** Aggregated permission resolvers of all addons. */
    val permissionResolvers: PermissionResolverRegistry = PermissionResolverRegistry()

    /** Installed addons. */
    val addonManager: AddonManager =
        AddonManager(paths.addons, registry, joinGates, permissionResolvers)

    private val overviewService = PlatformOverviewService(version(), taskStore, manager)
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
        ),
    )

    /**
     * Boots the node: loads tasks and addons, registers actions, starts the
     * control API and the auto-scaler.
     */
    fun start() {
        logger.info("Booting Helix-Cloud {} from {}", version(), dataDirectory.toAbsolutePath())
        taskStore.reload()
        BuiltinActions(
            paths = paths,
            taskStore = taskStore,
            manager = manager,
            routing = routing,
            overviewService = overviewService,
            versionCatalog = { VersionCatalog.load(dataDirectory) },
            shutdown = ::shutdown,
            commandQueue = commandQueue,
        ).registerAll(registry)
        AddonActions(addonManager).registerAll(registry)
        addonManager.loadAll()
        controlServer.start()
        manager.onServiceTerminated { service: ManagedService ->
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
        Runtime.getRuntime().addShutdownHook(Thread { stopServicesQuietly() })
        logger.info(
            "Node ready — dashboard: http://{}:{}/  token: {}",
            config.control.host,
            config.control.port,
            config.control.token,
        )
    }

    /**
     * Runs the interactive CLI until the input ends or `platform.stop` runs.
     */
    fun runCli() {
        NodeCli(registry).run(System.`in`.bufferedReader())
        shutdown()
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
            exitProcess(0)
        }, "helix-shutdown").start()
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

    private companion object {
        /** Seconds before the first auto-scaler pass. */
        const val SCALER_INITIAL_DELAY_SECONDS = 3L

        /** Seconds between auto-scaler passes. */
        const val SCALER_PERIOD_SECONDS = 5L

        /** Maximum milliseconds to wait for services during shutdown. */
        const val SHUTDOWN_WAIT_MILLIS = 15_000L
    }
}
