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
}
