package org.helix.api.action

/**
 * Executes a single action.
 *
 * Actions are the single interaction contract of Helix-Cloud: CLI, REST,
 * bridges and addons all trigger behaviour through registered handlers.
 */
fun interface ActionHandler {
    /**
     * Executes the action.
     *
     * @param invocation action name, arguments and source.
     * @return the outcome, never throws for expected failures.
     */
    fun execute(invocation: ActionInvocation): ActionResult
}
