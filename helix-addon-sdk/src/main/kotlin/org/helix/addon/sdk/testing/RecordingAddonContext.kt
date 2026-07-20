package org.helix.addon.sdk.testing

import java.nio.file.Path
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.DisplayResolver
import org.helix.api.addon.JoinGate
import org.helix.api.addon.NotificationListener
import org.helix.api.addon.PermissionResolver
import org.helix.api.addon.PlayerListener
import org.helix.api.player.OnlinePlayer
import org.helix.api.storage.AddonStorage
import org.helix.api.storage.InMemoryAddonStorage

/**
 * In-memory [AddonContext] for addon unit tests.
 *
 * Records every registration and invocation so tests can assert addon
 * behaviour without a running node.
 *
 * @property dataDirectory directory handed to the addon.
 */
class RecordingAddonContext(
    override val dataDirectory: Path,
    private val storageBackend: AddonStorage = InMemoryAddonStorage(),
) : AddonContext {
    /** Registered actions by name. */
    val handlers = linkedMapOf<String, Pair<ActionDescriptor, ActionHandler>>()

    /** Registered join gates. */
    val joinGates = mutableListOf<JoinGate>()

    /** Registered permission resolvers. */
    val permissionResolvers = mutableListOf<PermissionResolver>()

    /** Registered display resolvers. */
    val displayResolvers = mutableListOf<DisplayResolver>()

    /** Registered player listeners. */
    val playerListeners = mutableListOf<PlayerListener>()

    /** Published bridge values. */
    val bridgeValues = linkedMapOf<String, String>()

    /** Registered notification listeners. */
    val notificationListeners = mutableListOf<NotificationListener>()

    /** Published notifications as category to message pairs. */
    val notifications = mutableListOf<Pair<String, String>>()

    /** Invocations the addon made through [actions]. */
    val invocations = mutableListOf<ActionInvocation>()

    /** Result returned for addon-made invocations. */
    var invocationResult: (ActionInvocation) -> ActionResult = { ActionResult.ok() }

    /** Answer for [hasPermission] questions. */
    var permissionCheck: (String, String) -> Boolean = { _, _ -> false }

    /** Players reported as online. */
    val online = mutableListOf<OnlinePlayer>()

    /** Stable storage returned by [storage]. */
    val storage: AddonStorage get() = storageBackend

    override fun storage(): AddonStorage = storageBackend

    override val actions: ActionInvoker = object : ActionInvoker {
        override fun invoke(invocation: ActionInvocation): ActionResult {
            invocations += invocation
            return handlers[invocation.action]?.second?.execute(invocation)
                ?: invocationResult(invocation)
        }

        override fun descriptors(): List<ActionDescriptor> = handlers.values.map { it.first }
    }

    override fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler) {
        handlers[descriptor.name] = descriptor to handler
    }

    override fun registerJoinGate(gate: JoinGate) {
        joinGates += gate
    }

    override fun registerPermissionResolver(resolver: PermissionResolver) {
        permissionResolvers += resolver
    }

    override fun hasPermission(player: String, permission: String): Boolean =
        permissionCheck(player, permission)

    override fun onlinePlayers(): List<OnlinePlayer> = online.toList()

    override fun registerPlayerListener(listener: PlayerListener) {
        playerListeners += listener
    }

    override fun registerDisplayResolver(resolver: DisplayResolver) {
        displayResolvers += resolver
    }

    override fun publishBridgeValue(key: String, value: String) {
        bridgeValues[key] = value
    }

    override fun publishNotification(category: String, message: String) {
        notifications += category to message
        notificationListeners.forEach { it.onNotification(category, message) }
    }

    override fun registerNotificationListener(listener: NotificationListener) {
        notificationListeners += listener
    }

    /**
     * Executes a registered action like a player or the CLI would.
     *
     * @param action the action name.
     * @param arguments positional arguments.
     * @return the handler result.
     */
    fun run(action: String, vararg arguments: String): ActionResult =
        handlers.getValue(action).second.execute(ActionInvocation(action, arguments.toList()))
}
