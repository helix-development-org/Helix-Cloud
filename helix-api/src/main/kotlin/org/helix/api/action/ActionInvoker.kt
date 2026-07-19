package org.helix.api.action

/**
 * Entry point for invoking registered actions.
 */
interface ActionInvoker {
    /**
     * Executes the action named in [invocation].
     *
     * @param invocation action name, arguments and source.
     * @return the handler result, or a failed result for unknown actions.
     */
    fun invoke(invocation: ActionInvocation): ActionResult

    /**
     * Lists all registered actions.
     *
     * @return descriptors sorted by action name.
     */
    fun descriptors(): List<ActionDescriptor>
}
