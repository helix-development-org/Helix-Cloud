package org.helix.node.control

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionSource
import org.helix.api.message.MapMessages
import org.helix.api.message.Messages
import org.helix.node.actions.ActionRegistry
import org.helix.node.actions.PlayerCommandService
import org.helix.node.addons.AddonManager
import org.helix.node.control.auth.PanelAuthService
import org.helix.node.dashboard.DashboardPanelRegistry
import org.helix.node.display.BridgeValueStore
import org.helix.node.display.DisplayResolverRegistry
import org.helix.node.events.EventLog
import org.helix.node.gates.JoinGateRegistry
import org.helix.node.gates.NativePermissionCache
import org.helix.node.gates.NativePermissionProvider
import org.helix.node.gates.PermissionResolverRegistry
import org.helix.node.gates.PermissionService
import org.helix.node.logging.LogBuffer
import org.helix.node.audit.AuditLog
import org.helix.node.backup.BackupService
import org.helix.node.messages.MessageRegistry
import org.helix.node.platform.ApiMetrics
import org.helix.node.platform.MetricsHistory
import org.helix.node.platform.PlatformOverviewService
import org.helix.node.scheduler.JobScheduler
import org.helix.node.players.PlayerRegistry
import org.helix.node.proxy.ProxyCommandQueue
import org.helix.node.proxy.ProxyEventHub
import org.helix.node.proxy.ProxyRoutingService
import org.helix.node.services.ServiceManager
import org.helix.node.tasks.TaskStore

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
 * @property nativePermissions per-player Minecraft-native permission snapshots.
 * @property permissionService node-wide permission decisions (addon or native).
 * @property loginPermission permission required to sign in to the web panel.
 * @property codeTtlSeconds lifetime of an in-game login code.
 * @property sessionTtlSeconds lifetime of a web session.
 * @property loginMessage in-game message template (`{code}` substituted).
 * @property networkName provider of the network display name (`{network}`),
 *  panel-editable at runtime.
 * @property proxyScreens configurable proxy-level disconnect screens.
 * @property metrics bounded history of network metric samples for graphs.
 * @property apiMetrics rolling control-API performance stats.
 * @property onMessagesChanged invoked after a message bundle changed, with the
 *  owning addon id (lets the node refresh derived state such as the global
 *  `{prefix}`).
 * @property jobScheduler recurring scheduled jobs.
 * @property backups workspace backups of static services.
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
    val nativePermissions: NativePermissionCache = NativePermissionCache(),
    val permissionService: PermissionService =
        PermissionService(permissionResolvers, NativePermissionProvider(nativePermissions)),
    val playerRegistry: PlayerRegistry = PlayerRegistry(),
    val displayResolvers: DisplayResolverRegistry = DisplayResolverRegistry(),
    val bridgeValues: BridgeValueStore = BridgeValueStore(),
    val logBuffer: LogBuffer = LogBuffer(),
    val eventLog: EventLog = EventLog(),
    val dashboardPanels: DashboardPanelRegistry = DashboardPanelRegistry(),
    val messages: MessageRegistry = MessageRegistry(),
    val proxyEvents: ProxyEventHub = ProxyEventHub(),
    val audit: AuditLog = AuditLog(java.nio.file.Path.of("audit.jsonl")),
    val loginPermission: String = "helix.panel.login",
    val codeTtlSeconds: Long = 300,
    val sessionTtlSeconds: Long = 86_400,
    val loginMessage: String = "§b§lHelix §r§7» §fYour panel login code is §b{code}§7.",
    val networkName: () -> String = { "our network" },
    val proxyScreens: Messages = MapMessages(emptyMap()),
    val metrics: MetricsHistory = MetricsHistory(),
    val apiMetrics: ApiMetrics = ApiMetrics(),
    val onMessagesChanged: (addonId: String) -> Unit = {},
    val backups: BackupService = BackupService(
        java.nio.file.Path.of("backups"),
        java.nio.file.Path.of("services/static"),
    ),
    val jobScheduler: JobScheduler = JobScheduler(
        org.helix.node.storage.JsonStorageProvider().forAddon("scheduler", java.nio.file.Path.of("scheduler")),
        registry,
    ),
) {
    /** Player command execution shared by the internal routes. */
    val playerCommands: PlayerCommandService = PlayerCommandService(registry, permissionService)

    /** Web-panel Minecraft-account login and per-view authorization. */
    val panelAuth: PanelAuthService = PanelAuthService(
        adminToken = token,
        loginPermission = loginPermission,
        loginMessage = loginMessage,
        codeTtlMs = codeTtlSeconds * 1000,
        sessionTtlMs = sessionTtlSeconds * 1000,
        players = playerRegistry,
        permissions = permissionService,
        deliver = { name, text ->
            registry.invoke(
                ActionInvocation(
                    action = "player.message",
                    arguments = listOf(name, text),
                    source = ActionSource.SYSTEM,
                ),
            ).success
        },
    )
}
