package org.helix.api.action

import kotlinx.serialization.Serializable

/**
 * A single request to execute an action.
 *
 * @property action name of the action to execute.
 * @property arguments positional arguments passed to the handler.
 * @property source where the invocation originated.
 * @property actor real-account name the invocation is attributed to for
 *   auditing, when known (a REST call from an authenticated panel session);
 *   `null` falls back to the generic [source] label.
 * @property requestId idempotency key of a bridge invocation. The node executes
 *   each `(service, requestId)` pair at most once and answers repeated
 *   invocations with the original result for at least ten minutes, so a caller
 *   that got no answer (timeout, lost connection) may safely retry with the same
 *   id. `null` disables deduplication.
 */
@Serializable
data class ActionInvocation @JvmOverloads constructor(
    val action: String,
    val arguments: List<String> = emptyList(),
    val source: ActionSource = ActionSource.SYSTEM,
    val actor: String? = null,
    val requestId: String? = null,
)
