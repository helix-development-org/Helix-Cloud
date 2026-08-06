package org.helix.api.action

/**
 * Observes every action execution on the node, without being able to
 * change it.
 *
 * Registered through
 * [org.helix.api.addon.AddonContext.registerActionObserver] by addons that
 * audit or mirror invocations — for example a Discord addon forwarding an
 * audit trail of everything humans trigger. Observer exceptions are logged
 * and swallowed; they never fail the observed invocation.
 */
fun interface ActionObserver {
    /**
     * Called after an action was executed.
     *
     * @param invocation the executed invocation, including source and actor.
     * @param result the outcome the caller received.
     */
    fun onAction(invocation: ActionInvocation, result: ActionResult)
}
