package org.helix.addon.sdk

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.DashboardPanel
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
     * Set [playerCommand] to expose it as an in-game command `/<name>` (the
     * name must be dot-free); the handler then receives the player name as
     * first argument. [permission] gates the in-game command.
     *
     * @param name unique action name.
     * @param description one-line summary.
     * @param usage argument hint.
     * @param playerCommand whether proxy bridges register it as `/<name>`.
     * @param permission permission node required to run the in-game command.
     * @param handler executed on invocation.
     */
    protected fun action(
        name: String,
        description: String,
        usage: String = name,
        playerCommand: Boolean = false,
        permission: String? = null,
        handler: (ActionInvocation) -> ActionResult,
    ) {
        context.registerAction(ActionDescriptor(name, description, usage, playerCommand, permission), handler)
    }

    /**
     * Registers a dashboard page whose markup is loaded from an addon
     * classpath resource.
     *
     * @param id url-safe panel id.
     * @param title sidebar label.
     * @param resource classpath path of the panel html, for example
     *   `/panel.html`.
     * @param icon optional inner SVG markup for the sidebar icon.
     */
    protected fun panel(id: String, title: String, resource: String, icon: String = "") {
        val html = javaClass.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
            ?: error("panel resource not found: $resource")
        context.registerDashboardPanel(DashboardPanel(id = id, title = title, icon = icon, html = html))
    }
}
