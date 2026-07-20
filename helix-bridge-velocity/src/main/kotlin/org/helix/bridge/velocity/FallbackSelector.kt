package org.helix.bridge.velocity

/**
 * Pure fallback selection logic, shared by initial-server choice and
 * kick redirection.
 */
object FallbackSelector {
    /**
     * Picks the least loaded fallback-eligible server.
     *
     * @param candidates all registered servers.
     * @param exclude server name to skip, for example the origin of a kick.
     * @return the selected server name, or `null` when no fallback exists.
     */
    fun select(candidates: List<FallbackCandidate>, exclude: String? = null): String? =
        candidates
            .filter { it.fallbackEligible && it.name != exclude }
            .minByOrNull { it.players }
            ?.name
}
