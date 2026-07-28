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
import org.helix.api.addon.PlayerDataProvider
import org.helix.api.addon.PlayerListener
import org.helix.api.addon.ProfileInfoEntry
import org.helix.api.addon.ProfileInfoProvider
import org.helix.api.addon.ProfileSettingDescriptor
import org.helix.api.addon.ProfileSettingProvider
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

    /** Registered GDPR export/delete providers. */
    val playerDataProviders = mutableListOf<PlayerDataProvider>()

    /** Registered read-only profile-info providers. */
    val profileInfoProviders = mutableListOf<ProfileInfoProvider>()

    /** Registered interactive profile-setting providers. */
    val profileSettingProviders = mutableListOf<ProfileSettingProvider>()

    /** Changes delivered via [notifyProfileSettingChanged]. */
    val profileSettingChanges = mutableListOf<ProfileSettingChange>()

    /** One recorded [notifyProfileSettingChanged] call. */
    data class ProfileSettingChange(val owner: String, val player: String, val key: String, val value: String)

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

    /** Simulated identity registry: lowercase name to uuid. */
    val uuidsByName = mutableMapOf<String, String>()

    /** Simulated identity registry: uuid to last-known lowercase name. */
    val namesByUuid = mutableMapOf<String, String>()

    /** Stable storage returned by [storage]. */
    val storage: AddonStorage get() = storageBackend

    override fun storage(): AddonStorage = storageBackend

    override fun resolvePlayerUuid(name: String): String? = uuidsByName[name.lowercase()]

    override fun lastKnownName(uuid: String): String? = namesByUuid[uuid]

    /**
     * Simulates a join: records the name/uuid pair like the node's identity
     * registry would, including dropping a stale reverse mapping on rename.
     *
     * @param name player name as reported at join.
     * @param uuid player uuid.
     */
    fun recordJoin(name: String, uuid: String) {
        val lower = name.lowercase()
        namesByUuid[uuid]?.let { previous -> if (previous != lower) uuidsByName.remove(previous) }
        namesByUuid[uuid] = lower
        uuidsByName[lower] = uuid
    }

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

    override fun registerPlayerDataProvider(provider: PlayerDataProvider) {
        playerDataProviders += provider
    }

    override fun registerProfileInfoProvider(provider: ProfileInfoProvider) {
        profileInfoProviders += provider
    }

    override fun registerProfileSettingProvider(provider: ProfileSettingProvider) {
        profileSettingProviders += provider
    }

    override fun profileInfo(player: String): Map<String, List<ProfileInfoEntry>> =
        profileInfoProviders.mapIndexed { index, provider -> "provider-$index" to provider.infoFor(player) }
            .filter { (_, lines) -> lines.isNotEmpty() }
            .toMap()

    override fun profileSettings(player: String): Map<String, List<ProfileSettingDescriptor>> =
        profileSettingProviders.mapIndexed { index, provider -> "provider-$index" to provider.settingsFor(player) }
            .filter { (_, descriptors) -> descriptors.isNotEmpty() }
            .toMap()

    override fun notifyProfileSettingChanged(owner: String, player: String, key: String, value: String) {
        profileSettingChanges += ProfileSettingChange(owner, player, key, value)
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

    override fun unpublishBridgeValue(key: String) {
        bridgeValues.remove(key)
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
