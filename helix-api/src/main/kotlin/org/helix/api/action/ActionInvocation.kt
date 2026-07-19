package org.helix.api.action

import kotlinx.serialization.Serializable

/**
 * A single request to execute an action.
 *
 * @property action name of the action to execute.
 * @property arguments positional arguments passed to the handler.
 * @property source where the invocation originated.
 */
@Serializable
data class ActionInvocation(
    val action: String,
    val arguments: List<String> = emptyList(),
    val source: ActionSource = ActionSource.SYSTEM,
)
