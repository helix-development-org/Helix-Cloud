package org.helix.bridge.paper

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.wire.ServiceNodeApi

/**
 * Invokes bridge-invocable node actions through the service transport — the
 * action channel behind the Vault economy provider. Travels over Helix-Wire
 * when it is up, HTTP otherwise.
 *
 * @property api the bridge's node transport.
 */
class BridgeActionInvoker(private val api: ServiceNodeApi) {
    /**
     * Invokes one action.
     *
     * @param action the action name; must be `bridgeInvocable`.
     * @param arguments positional arguments.
     * @return the action result, or `null` when the node is unreachable.
     */
    fun invoke(action: String, arguments: List<String>): ActionResult? =
        api.action(ActionInvocation(action, arguments, ActionSource.BRIDGE))
}
