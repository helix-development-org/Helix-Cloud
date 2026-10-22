package org.helix.addons.guardlab.paper

import kotlinx.serialization.Serializable

/**
 * What one check did during a scenario.
 *
 * From IGuard's frame reports ([FrameStats]): [flags] are the counted failures, [total] their summed
 * weight, [failed] all failures including those the signal cooldown did not count. Without frame reports
 * ([ViolationTracker]): [flags] are ticks in which the level rose and [total] the summed increase.
 *
 * @property check check id.
 * @property flags counted failures (or ticks in which the violation level rose).
 * @property total summed violation weight (or summed increase).
 * @property peak highest level above the start baseline.
 * @property failed failures including uncounted ones (0 without frame reports).
 * @property maxVl highest absolute level (0 without frame reports).
 */
@Serializable
data class CheckObservation(
    val check: String,
    val flags: Int,
    val total: Double,
    val peak: Double,
    val failed: Int = 0,
    val maxVl: Double = 0.0,
)

/**
 * Accumulates per-tick violation levels into [CheckObservation]s.
 *
 * IGuard decays levels continuously, so only rises count: every tick the increase against the previous
 * tick is summed per check; [CheckObservation.peak] is the highest level above the level at the start.
 */
class ViolationTracker {
    private val baseline = mutableMapOf<String, Double>()
    private val previous = mutableMapOf<String, Double>()
    private val flags = mutableMapOf<String, Int>()
    private val totals = mutableMapOf<String, Double>()
    private val peaks = mutableMapOf<String, Double>()

    /**
     * Records the levels at scenario start.
     *
     * @param levels check → level.
     */
    fun start(levels: Map<String, Double>) {
        baseline.clear(); previous.clear(); flags.clear(); totals.clear(); peaks.clear()
        baseline.putAll(levels)
        previous.putAll(levels)
    }

    /**
     * Records one tick.
     *
     * @param levels check → level now.
     * @return check → increase this tick (only checks that rose).
     */
    fun sample(levels: Map<String, Double>): Map<String, Double> {
        val rises = mutableMapOf<String, Double>()
        // A level that decayed away vanishes from the snapshot; treat it as 0 so a fresh flag counts again.
        (previous.keys - levels.keys).forEach { previous[it] = 0.0 }
        levels.forEach { (check, level) ->
            val before = previous[check] ?: 0.0
            val rise = level - before
            if (rise > EPSILON) {
                rises[check] = rise
                flags[check] = (flags[check] ?: 0) + 1
                totals[check] = (totals[check] ?: 0.0) + rise
            }
            val aboveStart = level - (baseline[check] ?: 0.0)
            if (aboveStart > (peaks[check] ?: 0.0)) peaks[check] = aboveStart
            previous[check] = level
        }
        return rises
    }

    /** @return every check that rose at least once. */
    fun observations(): List<CheckObservation> =
        totals.keys.sorted().map { CheckObservation(it, flags[it] ?: 0, totals[it] ?: 0.0, peaks[it] ?: 0.0) }

    private companion object {
        const val EPSILON = 1e-4
    }
}

/**
 * Everything collected for one scenario.
 *
 * @property checks per-check observations.
 * @property setbacks setback teleports IGuard issued.
 * @property clientFinished the client reported `finished`.
 * @property clientTicks ticks the client played.
 * @property error client error or timeout.
 * @property skipped reason the scenario did not run.
 * @property avgPing mean server-side ping during playback (ms).
 * @property maxPing highest ping during playback (ms).
 * @property notes runner remarks (death, mount failure …) appended to the verdict.
 * @property frames IGuard frame-report summary (null when IGuard delivered no frame reports).
 */
@Serializable
data class ScenarioObservation(
    val checks: List<CheckObservation> = emptyList(),
    val setbacks: Int = 0,
    val clientFinished: Boolean = false,
    val clientTicks: Int = 0,
    val error: String? = null,
    val skipped: String? = null,
    val avgPing: Double = 0.0,
    val maxPing: Int = 0,
    val notes: List<String> = emptyList(),
    val frames: FrameSummary? = null,
)

/**
 * Outcome of one scenario. [GAP] is a missed detection of a scenario marked [Expectation.knownGap]: listed as
 * a known IGuard gap, not counted as a failure.
 */
enum class Status { PASS, WARN, FAIL, SKIP, GAP }

/**
 * Verdict of one scenario.
 *
 * @property status outcome.
 * @property reasons German explanations.
 * @property exempt a passing legit scenario that passed only because IGuard hardly checked
 *   (shown as `pass (exempt)`).
 */
@Serializable
data class Verdict(val status: Status, val reasons: List<String>, val exempt: Boolean = false)

/**
 * Compares observations with a scenario's expectation.
 *
 * - legit (`expect.none`): no rise → pass; rises that stayed below the check's alert level → warn
 *   (noise that still feeds confidence); any alert-level rise or an unexpected setback → fail;
 * - cheat: any of `checks` (or every one of `all`) must sum at least `minVl`; `setback` true/false is
 *   enforced when set. A miss of a [Expectation.knownGap] scenario becomes [Status.GAP], its detection a pass
 *   with the hint that the gap may be closed.
 * A client error starting with `unsupported` skips the scenario (the dev client lacks the mode). A passing
 * lag scenario whose measured ping stays far below the profile becomes a warning: the client is probably
 * not connected through Toxiproxy.
 *
 * A passing legit scenario is marked [Verdict.exempt] (`pass (exempt)`) when IGuard hardly checked it:
 * an ALL-scope exemption over more than [EXEMPT_SHARE] of the checked ticks, or – for scenarios that
 * exercise movement checks – skipped movement checks over more than [EXEMPT_SHARE] of the movement ticks
 * (or no movement frame at all). Alert levels reported in IGuard's frames take precedence over [judge]'s
 * `alertVl`.
 */
object Evaluator {
    /** Exempt share above which a passing legit scenario counts as not really checked. */
    const val EXEMPT_SHARE = 0.8

    /**
     * Judges one scenario.
     *
     * @param scenario the scenario.
     * @param observation what happened.
     * @param alertVl alert level of a check (from IGuard's config).
     * @return the verdict.
     */
    fun judge(scenario: Scenario, observation: ScenarioObservation, alertVl: (String) -> Double): Verdict {
        val alert = { check: String -> observation.frames?.activity(check)?.alertVl?.takeUnless(Double::isNaN) ?: alertVl(check) }
        val judged = markExempt(scenario, observation, judgeBehaviour(scenario, observation, alert))
        val verdict = if (observation.notes.isEmpty()) judged else judged.copy(reasons = judged.reasons + observation.notes)
        val expected = LagProfiles.expectedPing(scenario.lag) ?: return verdict
        if (verdict.status != Status.PASS || observation.avgPing >= expected * LAG_EFFECTIVE) return verdict
        return Verdict(
            Status.WARN,
            verdict.reasons + "Lag kaum messbar: Ø Ping %.0f ms statt ~%d ms – Client über Toxiproxy (Port 25570) verbunden?".format(observation.avgPing, expected),
        )
    }

    private const val LAG_EFFECTIVE = 0.5

    /**
     * Why a scenario was hardly checked, empty when IGuard checked it normally.
     *
     * @param scenario the scenario (its `covers` decide whether movement exemptions matter).
     * @param frames the frame summary.
     * @return German reasons.
     */
    fun notChecked(scenario: Scenario, frames: FrameSummary): List<String> {
        if (frames.frames == 0L) return emptyList()
        val why = mutableListOf<String>()
        if (frames.allExemptShare > EXEMPT_SHARE) why += "alle Checks ausgesetzt in ${percent(frames.allExemptShare)} der Ticks"
        val movement = scenario.covers.isEmpty() || scenario.covers.any { it.startsWith("movement.") }
        if (movement) {
            val share = frames.movementExemptShare
            when {
                share == null -> why += "keine Bewegungs-Frames – Bewegungs-Checks liefen nicht"
                share > EXEMPT_SHARE -> why += "Bewegungs-Checks ausgesetzt in ${percent(share)} der Ticks"
            }
        }
        return why
    }

    /**
     * Formats a share as percent.
     *
     * @param share 0..1.
     * @return e.g. `96 %`.
     */
    fun percent(share: Double): String = "%.0f %%".format(share * 100)

    private fun markExempt(scenario: Scenario, observation: ScenarioObservation, verdict: Verdict): Verdict {
        if (verdict.status != Status.PASS || !scenario.expect.none) return verdict
        val frames = observation.frames ?: return verdict
        val why = notChecked(scenario, frames)
        if (why.isEmpty()) return verdict
        val top = frames.exemptions.take(TOP_REASONS).joinToString { "${it.name} ${percent(it.share)}" }
        val detail = why.joinToString("; ") + if (top.isEmpty()) "" else " (exempt: $top)"
        return Verdict(Status.PASS, listOf("keine Meldung, aber kaum geprüft: $detail") + verdict.reasons.filter { it != NO_FLAG }, exempt = true)
    }

    private const val TOP_REASONS = 3
    private const val NO_FLAG = "keine Meldung"

    private fun judgeBehaviour(scenario: Scenario, observation: ScenarioObservation, alertVl: (String) -> Double): Verdict {
        observation.skipped?.let { return Verdict(Status.SKIP, listOf(it)) }
        observation.error?.let { error ->
            return if (error.startsWith("unsupported")) {
                Verdict(Status.SKIP, listOf("Client unterstützt das nicht: $error"))
            } else {
                Verdict(Status.FAIL, listOf("Client-Fehler: $error"))
            }
        }
        if (!observation.clientFinished) return Verdict(Status.FAIL, listOf("Client hat das Szenario nicht beendet (Timeout)"))
        val byCheck = observation.checks.associateBy { it.check }
        val expect = scenario.expect
        return if (expect.none) judgeLegit(observation, alertVl) else judgeCheat(expect, byCheck, observation)
    }

    private fun judgeLegit(observation: ScenarioObservation, alertVl: (String) -> Double): Verdict {
        val flagged = observation.checks.filter { it.flags > 0 }
        val reasons = mutableListOf<String>()
        val loud = flagged.filter { it.peak >= alertVl(it.check) }
        if (observation.setbacks > 0) reasons += "${observation.setbacks} Setback(s) bei legitimer Bewegung"
        loud.forEach { reasons += "Fehlalarm ${it.check}: Spitze %.2f ≥ Alarm %.1f (%d×, Summe %.2f)".format(it.peak, alertVl(it.check), it.flags, it.total) }
        if (reasons.isNotEmpty()) return Verdict(Status.FAIL, reasons)
        if (flagged.isNotEmpty()) {
            return Verdict(Status.WARN, flagged.map { "Rauschen ${it.check}: %d×, Summe %.2f, Spitze %.2f (unter Alarm %.1f)".format(it.flags, it.total, it.peak, alertVl(it.check)) })
        }
        return Verdict(Status.PASS, listOf(NO_FLAG))
    }

    private fun judgeCheat(expect: Expectation, byCheck: Map<String, CheckObservation>, observation: ScenarioObservation): Verdict {
        val reasons = mutableListOf<String>()
        var ok = true
        val total = { check: String -> byCheck[check]?.total ?: 0.0 }
        if (expect.checks.isNotEmpty()) {
            val hit = expect.checks.filter { total(it) >= expect.minVl }
            if (hit.isEmpty()) {
                ok = false
                reasons += "nicht erkannt: erwartet eins von ${expect.checks} mit ≥ %.1f".format(expect.minVl)
            } else {
                reasons += "erkannt: " + hit.joinToString { "$it (%.2f)".format(total(it)) }
            }
        }
        expect.all.forEach { check ->
            if (total(check) < expect.minVl) { ok = false; reasons += "nicht erkannt: $check (%.2f < %.1f)".format(total(check), expect.minVl) }
        }
        when (expect.setback) {
            true -> if (observation.setbacks == 0) { ok = false; reasons += "kein Setback, obwohl erwartet" }
            false -> if (observation.setbacks > 0) { ok = false; reasons += "${observation.setbacks} unerwartete Setback(s)" }
            null -> {}
        }
        val others = byCheck.values.filter { it.flags > 0 && it.check !in expect.checks && it.check !in expect.all }
        if (others.isNotEmpty()) reasons += "außerdem gemeldet: " + others.joinToString { "${it.check} (%.2f)".format(it.total) }
        if (expect.note.isNotBlank()) reasons += "Hinweis: ${expect.note}"
        if (expect.knownGap) {
            return if (ok) {
                Verdict(Status.PASS, reasons + "bekannte Lücke erkannt – geschlossen? Dann `knownGap` entfernen")
            } else {
                Verdict(Status.GAP, listOf("bekannte Lücke") + reasons)
            }
        }
        return Verdict(if (ok) Status.PASS else Status.FAIL, reasons)
    }
}
