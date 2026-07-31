package org.helix.addons.discord

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvoker

/**
 * Read model over the node's action registry for the dynamic action
 * browser: actions grouped by their name prefix, with usage-derived
 * argument hints.
 *
 * @property actions the node's action entry point.
 */
class ActionCatalog(private val actions: ActionInvoker) {
    /**
     * All group names: the dot-separated prefix of each action, dot-free
     * player commands under [COMMANDS_GROUP].
     *
     * @return sorted, distinct group names.
     */
    fun groups(): List<String> =
        actions.descriptors().map(::groupOf).distinct().sorted()

    /**
     * The actions of one group.
     *
     * @param group the group name.
     * @return matching descriptors sorted by name.
     */
    fun actionsIn(group: String): List<ActionDescriptor> =
        actions.descriptors().filter { groupOf(it) == group }.sortedBy { it.name }

    /**
     * Looks an action up by name.
     *
     * @param name the action name.
     * @return the descriptor, or `null`.
     */
    fun find(name: String): ActionDescriptor? =
        actions.descriptors().firstOrNull { it.name == name }

    /**
     * The argument part of a descriptor's usage, shown as the input hint
     * of the run modal.
     *
     * @param descriptor the action.
     * @return the hint, for example `<task>`, or empty for no arguments.
     */
    fun argumentHint(descriptor: ActionDescriptor): String =
        descriptor.usage.removePrefix(descriptor.name).trim()

    private fun groupOf(descriptor: ActionDescriptor): String =
        if ("." in descriptor.name) descriptor.name.substringBefore('.') else COMMANDS_GROUP

    companion object {
        /** Group of dot-free player-command actions. */
        const val COMMANDS_GROUP = "commands"
    }
}

/**
 * Parsers for the line-based outputs of the built-in actions, feeding the
 * select menus of the curated modules.
 */
object CloudLines {
    private val SERVICE_LINE = Regex("""^(\S+) \[(\S+)] port=(\S+) players=(\S+) executor=.*$""")
    private val TASK_LINE = Regex("""^(\S+) \[.*] executor=.*$""")

    /**
     * A parsed `service.list` line.
     *
     * @property id the service id.
     * @property state the service state, for example `RUNNING`.
     * @property players the `online/max` player counter.
     */
    data class ServiceLine(val id: String, val state: String, val players: String)

    /**
     * Parses `service.list` output.
     *
     * @param lines the action result lines.
     * @return the parsed services; unparseable lines are skipped.
     */
    fun services(lines: List<String>): List<ServiceLine> =
        lines.mapNotNull { line ->
            SERVICE_LINE.matchEntire(line.trim())?.let {
                ServiceLine(it.groupValues[1], it.groupValues[2], it.groupValues[4])
            }
        }

    /**
     * Parses `task.list` output into task names.
     *
     * @param lines the action result lines.
     * @return the task names; unparseable lines are skipped.
     */
    fun taskNames(lines: List<String>): List<String> =
        lines.mapNotNull { line -> TASK_LINE.matchEntire(line.trim())?.groupValues?.get(1) }
}
