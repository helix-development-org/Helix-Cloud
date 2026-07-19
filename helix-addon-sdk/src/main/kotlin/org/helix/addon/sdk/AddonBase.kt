package org.helix.addon.sdk

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.HelixAddon

/**
 * Convenience base class for addons.
 *
 * Stores the [AddonContext] and offers a compact action registration
 * helper, so a minimal addon only implements [enable].
 */
abstract class AddonBase : HelixAddon {
    /** Node facilities, available from [enable] on. */
    protected lateinit var context: AddonContext
        private set

    /**
     * Stores the context and delegates to [enable].
     *
     * @param context node facilities scoped to this addon.
     */
    final override fun onEnable(context: AddonContext) {
        this.context = context
        enable()
    }

    /**
     * Called once when the addon is enabled.
     */
    protected abstract fun enable()

    /**
     * Registers an action owned by this addon.
     *
     * @param name unique action name.
     * @param description one-line summary.
     * @param usage argument hint.
     * @param handler executed on invocation.
     */
    protected fun action(
        name: String,
        description: String,
        usage: String = name,
        handler: (ActionInvocation) -> ActionResult,
    ) {
        context.registerAction(ActionDescriptor(name, description, usage), handler)
    }
}
