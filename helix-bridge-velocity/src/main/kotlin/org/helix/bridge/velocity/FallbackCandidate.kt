package org.helix.bridge.velocity

/**
 * A fallback candidate as seen by the proxy.
 *
 * @property name registered server name (the service id).
 * @property players players currently connected through the proxy.
 * @property fallbackEligible whether players may be sent here as fallback.
 */
data class FallbackCandidate(
    val name: String,
    val players: Int,
    val fallbackEligible: Boolean,
)
