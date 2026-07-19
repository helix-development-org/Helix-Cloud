package org.helix.api.addon

import java.nio.file.Path
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvoker

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
}
