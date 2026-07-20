package org.helix.node.control

import org.helix.node.actions.ActionRegistry
import org.helix.node.actions.PlayerCommandService
import org.helix.node.addons.AddonManager
import org.helix.node.dashboard.DashboardPanelRegistry
import org.helix.node.display.BridgeValueStore
import org.helix.node.display.DisplayResolverRegistry
import org.helix.node.events.EventLog
import org.helix.node.gates.JoinGateRegistry
import org.helix.node.gates.PermissionResolverRegistry
import org.helix.node.logging.LogBuffer
import org.helix.node.messages.MessageRegistry
import org.helix.node.platform.PlatformOverviewService
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
    val playerRegistry: PlayerRegistry = PlayerRegistry(),
    val displayResolvers: DisplayResolverRegistry = DisplayResolverRegistry(),
    val bridgeValues: BridgeValueStore = BridgeValueStore(),
    val logBuffer: LogBuffer = LogBuffer(),
    val eventLog: EventLog = EventLog(),
    val dashboardPanels: DashboardPanelRegistry = DashboardPanelRegistry(),
    val messages: MessageRegistry = MessageRegistry(),
    val proxyEvents: ProxyEventHub = ProxyEventHub(),
) {
    /** Player command execution shared by the internal routes. */
    val playerCommands: PlayerCommandService = PlayerCommandService(registry, permissionResolvers)
}
