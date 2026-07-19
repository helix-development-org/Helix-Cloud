package org.helix.api.addon

import java.nio.file.Path
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvoker
import org.helix.api.player.OnlinePlayer

/**
 * Node facilities handed to an addon on enable.
 */
interface AddonContext {
    /** Directory the addon may persist data in, created before enable. */
    val dataDirectory: Path

    /** Invoker for calling any registered action from the addon. */
    val actions: ActionInvoker

    /**
     * Registers an action owned by this addon.
     *
     * Registered actions are removed again when the addon is disabled.
     *
     * @param descriptor name, description and usage of the action.
     * @param handler executed on invocation.
     */
    fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler)

    /**
     * Registers a join gate owned by this addon.
     *
     * Proxy bridges ask the node on every login; the node evaluates all
     * registered gates. Gates are removed when the addon is disabled.
     *
     * @param gate evaluated on every join attempt.
     */
    fun registerJoinGate(gate: JoinGate)

    /**
     * Registers a permission resolver owned by this addon.
     *
     * Bridges and other addons ask the node for permissions; the node
     * grants when any resolver grants. Resolvers are removed when the
     * addon is disabled.
     *
     * @param resolver evaluated on every permission question.
     */
    fun registerPermissionResolver(resolver: PermissionResolver)

    /**
     * Asks the aggregated permission resolvers whether a player has a
     * permission.
     *
     * @param player player name.
     * @param permission permission node.
     * @return `true` when any registered resolver grants.
     */
    fun hasPermission(player: String, permission: String): Boolean = false

    /**
     * Lists all players currently connected to the network.
     *
     * @return online players sorted by name.
     */
    fun onlinePlayers(): List<OnlinePlayer> = emptyList()

    /**
     * Registers a network-wide player join/leave listener owned by this
     * addon. Removed when the addon is disabled.
     *
     * @param listener receives join and leave events.
     */
    fun registerPlayerListener(listener: PlayerListener) {
    }

    /**
     * Registers a display resolver owned by this addon. Removed when the
     * addon is disabled.
     *
     * @param resolver resolves chat/tab display profiles.
     */
    fun registerDisplayResolver(resolver: DisplayResolver) {
    }

    /**
     * Publishes a global bridge value, for example a tab list header.
     *
     * Bridges poll all published values; publishing the same key again
     * overwrites it. Values are removed when the addon is disabled.
     *
     * @param key value key, for example `tablist.header`.
     * @param value value text; `&` color codes are rendered by bridges.
     */
    fun publishBridgeValue(key: String, value: String) {
    }

    /**
     * Publishes a notification to all registered listeners.
     *
     * Use this for events other addons may want to react to — bans,
     * warns, kicks and similar belong into the `moderation` category.
     *
     * @param category notification category, for example `moderation`.
     * @param message human readable text, `&` color codes allowed.
     */
    fun publishNotification(category: String, message: String) {
    }

    /**
     * Registers a notification listener owned by this addon. Removed when
     * the addon is disabled.
     *
     * @param listener receives all published notifications.
     */
    fun registerNotificationListener(listener: NotificationListener) {
    }
}
