package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * Aggregated verdict of all registered join gates.
 *
 * @property allowed whether the player may join.
 * @property message denial message shown to the player.
 */
@Serializable
data class JoinDecision(
    val allowed: Boolean,
    val message: String? = null,
) {
    companion object {
        /**
         * Creates an allow decision.
         *
         * @return an allowing [JoinDecision].
         */
        fun allow(): JoinDecision = JoinDecision(allowed = true)

        /**
         * Creates a deny decision.
         *
         * @param message denial message shown to the player.
         * @return a denying [JoinDecision].
         */
        fun deny(message: String): JoinDecision = JoinDecision(allowed = false, message = message)
    }
}
