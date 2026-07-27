package de.tytoss.iguard.check

import de.tytoss.iguard.config.ConfidenceConfig
import de.tytoss.iguard.model.EvidenceFamily

internal object ConfidenceModel {
    // Bump whenever confidence weights / thresholds materially change so shadow decisions and the
    // calibrated-recipe gate stay attributable to a specific tuning.
    const val RECIPE_VERSION = "shadow-v3"

    /** Structural family of a check id — not a tunable, stays in code. */
    fun family(checkId: String): EvidenceFamily = when {
        checkId.startsWith("client.") -> EvidenceFamily.CLIENT
        checkId.startsWith("protocol.") -> EvidenceFamily.PROTOCOL
        checkId.startsWith("movement.") -> EvidenceFamily.MOVEMENT
        checkId.startsWith("combat.") -> EvidenceFamily.COMBAT
        else -> EvidenceFamily.WORLD
    }

    /** Per-signal confidence resolved from live (hot-reloadable) config. */
    fun signalConfidence(config: ConfidenceConfig, checkId: String): Double =
        config.signal[checkId] ?: config.defaultSignal

    /**
     * Noisy-OR across per-family max scores. Single-family incidents are capped below the shadow
     * threshold (so heuristic evidence needs corroboration from a second family); deterministic
     * evidence reaches a fixed proof tier on its own. All bounds come from config.
     */
    fun provisionalConfidence(config: ConfidenceConfig, familyScores: Collection<Double>, deterministic: Boolean): Double {
        if (deterministic) return config.deterministic
        if (familyScores.isEmpty()) return 0.0
        val combined = 1.0 - familyScores.fold(1.0) { remaining, score -> remaining * (1.0 - score.coerceIn(0.0, 0.99)) }
        return if (familyScores.size < 2) combined.coerceAtMost(config.singleFamilyCap) else combined.coerceAtMost(config.multiFamilyCap)
    }
}
