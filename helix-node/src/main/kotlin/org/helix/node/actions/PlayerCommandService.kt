package org.helix.node.actions

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.action.PlayerCommandRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.node.gates.PermissionResolverRegistry

/**
 * Executes player commands forwarded by proxy bridges.
 *
 * A player command is an action whose descriptor has `playerCommand`
 * set. The service checks the descriptor's permission (when present)
 * against the aggregated permission resolvers and invokes the action with
 * the player name as first argument.
 *
 * @property registry action registry.
 * @property permissions aggregated permission resolvers.
 */
class PlayerCommandService(
    private val registry: ActionRegistry,
    private val permissions: PermissionResolverRegistry,
) {
    /**
     * Lists all player-command actions.
     *
     * @return descriptors sorted by name.
     */
    fun commands(): List<ActionDescriptor> = registry.descriptors().filter { it.playerCommand }

    /**
     * Executes one player command.
     *
     * @param request player, command and arguments from the bridge.
     * @return the action result, or a failure for unknown commands and
     *   missing permissions.
     */
    fun execute(request: PlayerCommandRequest): ActionResult {
        val descriptor = commands().firstOrNull { it.name == request.command }
            ?: return ActionResult.error("unknown command: ${request.command}")
        val required = descriptor.permission
        if (required != null && !permissions.evaluate(PermissionCheckRequest(request.player, required))) {
            return ActionResult.error("You do not have permission to do that.")
        }
        return registry.invoke(
            ActionInvocation(
                action = descriptor.name,
                arguments = listOf(request.player) + request.arguments,
                source = ActionSource.BRIDGE,
            ),
        )
    }
}
