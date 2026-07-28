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
     * @param bypassMaintenance whether a maintenance-flagged backend may
     *  still be selected (holders of `helix.maintenance.bypass`).
     * @return the selected server name, or `null` when no fallback exists.
     */
    fun select(candidates: List<FallbackCandidate>, exclude: String? = null, bypassMaintenance: Boolean = false): String? =
        candidates
            .filter { it.fallbackEligible && it.name != exclude && (bypassMaintenance || !it.maintenance) }
            .minByOrNull { it.players }
            ?.name
}
