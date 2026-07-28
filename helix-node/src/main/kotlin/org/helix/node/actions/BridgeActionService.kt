package org.helix.node.actions

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Executes actions invoked by a Paper/Velocity component holding a
 * per-service token, through `POST /internal/action`.
 *
 * Only actions whose descriptor has `bridgeInvocable` set may be invoked
 * this way — everything else answers a failure, so a per-service token
 * (present in every managed game server's environment) can not reach
 * admin-only actions the way an admin-token or `helix.admin` session could
 * via `POST /api/v1/actions`.
 *
 * @property registry action registry.
 */
class BridgeActionService(private val registry: ActionRegistry) {
    /**
     * Executes one bridge-invocable action.
     *
     * @param invocation action name and arguments from the bridge.
     * @return the action result, or a failure for unknown or non-bridge
     *   actions.
     */
    fun invoke(invocation: ActionInvocation): ActionResult {
        val descriptor = registry.descriptors().firstOrNull { it.name == invocation.action }
        if (descriptor == null || !descriptor.bridgeInvocable) {
            return ActionResult.error("unknown or non-bridge action: ${invocation.action}")
        }
        return registry.invoke(invocation.copy(source = ActionSource.BRIDGE))
    }
}
