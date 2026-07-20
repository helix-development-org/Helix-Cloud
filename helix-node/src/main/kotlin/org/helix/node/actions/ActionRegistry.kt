package org.helix.node.actions

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.slf4j.LoggerFactory

/**
 * Central registry of all actions on the node.
 *
 * CLI, REST, bridges and addons execute behaviour exclusively through this
 * registry. Handler exceptions are converted into failed results so one
 * broken action can not take down a caller.
 */
class ActionRegistry : ActionInvoker {
    private val logger = LoggerFactory.getLogger(ActionRegistry::class.java)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    private val invocationListeners = CopyOnWriteArrayList<(ActionInvocation, ActionResult) -> Unit>()

    private data class Entry(val descriptor: ActionDescriptor, val handler: ActionHandler)

    /**
     * Registers a listener invoked whenever the set of actions changes,
     * used to notify proxies about new or removed player-commands.
     *
     * @param listener called after every register/unregister.
     */
    fun onChange(listener: () -> Unit) {
        changeListeners += listener
    }

    /**
     * Registers a listener invoked after every action execution, used to
     * audit all invocations.
     *
     * @param listener receives the invocation and its result.
     */
    fun onInvocation(listener: (ActionInvocation, ActionResult) -> Unit) {
        invocationListeners += listener
    }

    /**
     * Registers an action.
     *
     * @param descriptor name, description and usage.
     * @param handler executed on invocation.
     * @throws IllegalArgumentException if the name is already registered.
     */
    fun register(descriptor: ActionDescriptor, handler: ActionHandler) {
        val previous = entries.putIfAbsent(descriptor.name, Entry(descriptor, handler))
        require(previous == null) { "action already registered: ${descriptor.name}" }
        changeListeners.forEach { it() }
    }

    /**
     * Removes an action, for example when an addon is disabled.
     *
     * @param name the action name.
     * @return `true` if the action existed.
     */
    fun unregister(name: String): Boolean {
        val removed = entries.remove(name) != null
        if (removed) {
            changeListeners.forEach { it() }
        }
        return removed
    }

    /**
     * Executes the action named in [invocation].
     *
     * @param invocation action name, arguments and source.
     * @return the handler result; failed results for unknown actions and
     *   handler exceptions.
     */
    override fun invoke(invocation: ActionInvocation): ActionResult {
        val entry = entries[invocation.action]
        val result = if (entry == null) {
            ActionResult.error("unknown action: ${invocation.action}")
        } else {
            try {
                entry.handler.execute(invocation)
            } catch (failure: Exception) {
                logger.error("Action {} failed", invocation.action, failure)
                ActionResult.error("${invocation.action} failed: ${failure.message}")
            }
        }
        invocationListeners.forEach { it(invocation, result) }
        return result
    }

    /**
     * Lists all registered actions.
     *
     * @return descriptors sorted by action name.
     */
    override fun descriptors(): List<ActionDescriptor> =
        entries.values.map { it.descriptor }.sortedBy { it.name }
}
