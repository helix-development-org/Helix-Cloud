package org.helix.bridge.paper

import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Invokes bridge-invocable node actions over `POST /api/v1/internal/action`
 * with the bridge's per-service token — the action channel behind the
 * Vault economy provider.
 *
 * @property client the bridge's node HTTP client.
 */
class BridgeActionInvoker(private val client: NodeHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Invokes one action.
     *
     * @param action the action name; must be `bridgeInvocable`.
     * @param arguments positional arguments.
     * @return the action result, or `null` when the node is unreachable.
     */
    fun invoke(action: String, arguments: List<String>): ActionResult? {
        val body = json.encodeToString(ActionInvocation(action, arguments, ActionSource.BRIDGE))
        val response = client.postJsonForBody("/api/v1/internal/action", body) ?: return null
        return runCatching { json.decodeFromString<ActionResult>(response) }.getOrNull()
    }
}
