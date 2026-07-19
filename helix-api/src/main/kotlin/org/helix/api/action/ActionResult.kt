package org.helix.api.action

import kotlinx.serialization.Serializable

/**
 * Outcome of an action execution.
 *
 * @property success whether the action completed successfully.
 * @property lines human readable output lines.
 */
@Serializable
data class ActionResult(
    val success: Boolean,
    val lines: List<String> = emptyList(),
) {
    companion object {
        /**
         * Creates a successful result.
         *
         * @param lines output lines shown to the caller.
         * @return a successful [ActionResult].
         */
        fun ok(vararg lines: String): ActionResult = ActionResult(success = true, lines = lines.toList())

        /**
         * Creates a failed result.
         *
         * @param lines error lines shown to the caller.
         * @return a failed [ActionResult].
         */
        fun error(vararg lines: String): ActionResult = ActionResult(success = false, lines = lines.toList())
    }
}
