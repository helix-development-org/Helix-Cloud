package org.helix.addons.guardlab.paper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The IGuard check ids GuardLab knows, and those a scripted client cannot provoke. */
object CheckCatalog {
    /** All 32 IGuard checks. */
    val ALL: List<String> = listOf(
        "client.identity.a", "client.brand_spoof.a", "protocol.badpackets.a",
        "movement.fly.a", "movement.speed.a", "movement.nofall.a", "movement.timer.a", "movement.phase.a",
        "movement.step.a", "movement.spider.a", "movement.jesus.a", "movement.velocity.a", "movement.airjump.a",
        "movement.sprintbackwards.a", "movement.fastladder.a", "movement.highjump.a", "movement.elytrafly.a",
        "combat.reach.a", "combat.rotation.a", "combat.multitarget.a", "combat.autoclicker.a", "combat.noswing.a",
        "combat.snapaim.a", "combat.inventory.a",
        "world.interactionreach.a", "world.scaffold.a", "world.fastplace.a", "world.fastbreak.a", "world.nuker.a",
        "world.nofacing.a",
        "inventory.impossible.a", "inventory.move.a",
    )

    /**
     * Checks without a cheat scenario, with the reason (they are still covered by legit scenarios). Empty since
     * the client contract gained `phase`, `omnisprint`, `inventory`, `identity`, `brandspoof` and `badpackets`.
     */
    val NOT_PROVOKED: Map<String, String> = emptyMap()
}

/**
 * Result of one scenario.
 *
 * @property index position in the run.
 * @property id scenario id.
 * @property title German title.
 * @property category `legit`, `cheat` or `lag`.
 * @property cheat cheat mode.
 * @property lag lag profile.
 * @property status outcome.
 * @property reasons explanations.
 * @property expected expected checks (empty for legit).
 * @property observation raw observation.
 * @property trace GuardLab trace file (one line per server tick) relative to the report directory.
 * @property startedAt start (ISO-8601).
 * @property durationMs wall time.
 * @property exempt passed only because IGuard hardly checked (`pass (exempt)`, see [Verdict.exempt]).
 * @property iguardTrace copy of IGuard's own trace (one line per frame) relative to the report directory.
 * @property expectation German description of the expectation (see [Reports.expectation]).
 * @property covers checks the scenario exercises (legit) or expects (cheat).
 */
@Serializable
data class ScenarioResult(
    val index: Int,
    val id: String,
    val title: String,
    val category: String,
    val cheat: String,
    val lag: String,
    val status: Status,
    val reasons: List<String>,
    val expected: List<String>,
    val observation: ScenarioObservation,
    val trace: String? = null,
    val startedAt: String = "",
    val durationMs: Long = 0,
    val exempt: Boolean = false,
    val iguardTrace: String? = null,
    val expectation: String = "",
    val covers: List<String> = emptyList(),
)

/**
 * Header of a run.
 *
 * @property suite suite id.
 * @property player client name.
 * @property clientVersion client version (`ready`).
 * @property clientBrand client brand (`ready`).
 * @property service test service id.
 * @property startedAt start (ISO-8601).
 * @property finishedAt end (ISO-8601).
 * @property aborted abort reason, null when the run completed.
 * @property dummy whether a dummy client was connected.
 * @property toxiproxy Toxiproxy status (`ok` or the problem).
 * @property probe how violations were read.
 * @property sanctions IGuard sanctions mode.
 */
@Serializable
data class RunInfo(
    val suite: String,
    val player: String,
    val clientVersion: String = "",
    val clientBrand: String = "",
    val service: String = "",
    val startedAt: String = "",
    val finishedAt: String = "",
    val aborted: String? = null,
    val dummy: Boolean = false,
    val toxiproxy: String = "",
    val probe: String = "",
    val sanctions: String = "",
)

/**
 * Coverage of one check in a run.
 *
 * @property check check id.
 * @property legit legit/lag scenarios of the run that exercise it.
 * @property cheat scenarios of the run that expect it.
 * @property detected expecting scenarios where it reached the expected level.
 * @property flaggedLegit legit scenarios where it rose at all.
 * @property state `ok`, `nicht erkannt`, `Fehlalarm`, `Rauschen`, `nur legit` or `ungetestet`.
 * @property note why there is no cheat scenario.
 */
@Serializable
data class CoverageRow(
    val check: String,
    val legit: List<String>,
    val cheat: List<String>,
    val detected: List<String>,
    val flaggedLegit: List<String>,
    val state: String,
    val note: String = "",
)

/**
 * Threshold calibration of one check: how high legit play drove it versus how low cheats stayed.
 *
 * @property check check id.
 * @property alertVl the check's alert level.
 * @property legitMax highest level above the start baseline in a legit scenario that exercised the check
 *   (null without such a scenario).
 * @property legitScenario scenario of [legitMax].
 * @property cheatMin lowest level a cheat scenario expecting the check reached (null without one).
 * @property cheatScenario scenario of [cheatMin].
 * @property verdict German assessment (`passt`, `Schwelle zu niedrig` …).
 */
@Serializable
data class CalibrationRow(
    val check: String,
    val alertVl: Double,
    val legitMax: Double?,
    val legitScenario: String?,
    val cheatMin: Double?,
    val cheatScenario: String?,
    val verdict: String,
)

/**
 * A complete run.
 *
 * @property info header.
 * @property results scenario results in run order.
 * @property coverage per-check coverage.
 * @property calibration per-check threshold calibration (checks with data only).
 */
@Serializable
data class RunReport(
    val info: RunInfo,
    val results: List<ScenarioResult>,
    val coverage: List<CoverageRow>,
    val calibration: List<CalibrationRow> = emptyList(),
) {
    /** Passing scenarios that passed only because IGuard hardly checked (`pass (exempt)`). */
    val exemptPasses: Int get() = results.count { it.status == Status.PASS && it.exempt }

    /**
     * Number of results with [status].
     *
     * @param status the status.
     * @return the count.
     */
    fun count(status: Status): Int = results.count { it.status == status }
}

/** Builds coverage and renders reports. */
object Reports {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Computes the coverage matrix.
     *
     * @param scenarios scenarios of the run (id → scenario).
     * @param results their results.
     * @return one row per known check.
     */
    fun coverage(scenarios: Map<String, Scenario>, results: List<ScenarioResult>): List<CoverageRow> = CheckCatalog.ALL.map { check ->
        val ran = results.filter { it.status != Status.SKIP }
        val legit = ran.filter { scenarios[it.id]?.let { s -> s.expect.none && check in s.covers } == true }.map { it.id }
        // Known gaps are listed on their own; they must not turn a check into "nicht erkannt".
        val expecting = ran.filter { it.status != Status.GAP }
            .filter { scenarios[it.id]?.let { s -> check in s.expect.checks || check in s.expect.all } == true }
        val detected = expecting.filter { r ->
            val min = scenarios[r.id]?.expect?.minVl ?: 1.0
            (r.observation.checks.firstOrNull { it.check == check }?.total ?: 0.0) >= min
        }.map { it.id }
        val flagged = ran.filter { scenarios[it.id]?.expect?.none == true && it.observation.checks.any { c -> c.check == check && c.flags > 0 } }
        val alarms = flagged.filter { it.status == Status.FAIL }
        val state = when {
            alarms.isNotEmpty() -> "Fehlalarm"
            expecting.isNotEmpty() && detected.isEmpty() -> "nicht erkannt"
            flagged.isNotEmpty() -> "Rauschen"
            expecting.isNotEmpty() -> "ok"
            legit.isNotEmpty() -> "nur legit"
            else -> "ungetestet"
        }
        CoverageRow(check, legit, expecting.map { it.id }, detected, flagged.map { it.id }, state, CheckCatalog.NOT_PROVOKED[check].orEmpty())
    }

    /**
     * Computes the threshold calibration: per check the highest level legit scenarios reached against
     * the lowest level of the cheat scenarios expecting it, both compared with the alert level.
     *
     * Legit scenarios count when the check rose there or IGuard evaluated it (without frame reports: when
     * they cover it); `pass (exempt)` scenarios do not count. A cheat scenario counts for every check of
     * `expect.all`; for `expect.checks` ("one of") only for the checks that rose, or for all of them when
     * none did. Skipped scenarios, client errors and known gaps ([Status.GAP]) are ignored.
     *
     * @param scenarios scenarios of the run (id → scenario).
     * @param results their results.
     * @param alertVl alert level of a check.
     * @return one row per check with data, in catalogue order.
     */
    fun calibration(scenarios: Map<String, Scenario>, results: List<ScenarioResult>, alertVl: (String) -> Double): List<CalibrationRow> {
        val ran = results.filter { it.status != Status.SKIP && it.status != Status.GAP && it.observation.error == null }
        val seen = ran.flatMap { r -> r.observation.checks.map { it.check } + r.observation.frames?.checks.orEmpty().map { it.check } }
        val checks = (CheckCatalog.ALL + seen.sorted()).distinct()
        return checks.mapNotNull { check ->
            val peak = { r: ScenarioResult -> r.observation.checks.firstOrNull { it.check == check }?.peak ?: 0.0 }
            val legit = ran.filter { r ->
                val scenario = scenarios[r.id] ?: return@filter false
                val frames = r.observation.frames
                val exercised = r.observation.checks.any { it.check == check } ||
                    if (frames == null) check in scenario.covers else (frames.activity(check)?.evaluated ?: 0) > 0
                scenario.expect.none && !r.exempt && exercised
            }
            val cheat = ran.filter { r ->
                val expect = scenarios[r.id]?.expect ?: return@filter false
                when {
                    expect.none -> false
                    check in expect.all -> true
                    check !in expect.checks -> false
                    expect.checks.size == 1 || peak(r) > 0.0 -> true
                    else -> expect.checks.none { c -> (r.observation.checks.firstOrNull { it.check == c }?.peak ?: 0.0) > 0.0 }
                }
            }
            if (legit.isEmpty() && cheat.isEmpty()) return@mapNotNull null
            val alert = alertVl(check)
            val loudest = legit.maxByOrNull(peak)
            val quietest = cheat.minByOrNull(peak)
            val legitMax = loudest?.let(peak)
            val cheatMin = quietest?.let(peak)
            CalibrationRow(check, alert, legitMax, loudest?.id, cheatMin, quietest?.id, calibrationVerdict(alert, legitMax, cheatMin))
        }
    }

    private fun calibrationVerdict(alert: Double, legitMax: Double?, cheatMin: Double?): String {
        val loud = legitMax != null && legitMax >= alert
        val quiet = cheatMin != null && cheatMin < alert
        return when {
            loud && quiet -> "passt nicht: legit erreicht den Alarm, ein Cheat bleibt darunter"
            loud -> "Schwelle zu niedrig: Fehlalarm bei legit"
            cheatMin != null && cheatMin <= 0.0 -> "nicht erkannt: ein Cheat ohne jeden Anstieg"
            quiet -> "Schwelle zu hoch: ein Cheat bleibt darunter"
            legitMax != null && cheatMin != null -> "passt (Luft: legit %.2f unter, Cheat %.2f über dem Alarm)".format(alert - legitMax, cheatMin - alert)
            legitMax != null -> "nur legit-Daten (%.2f unter dem Alarm)".format(alert - legitMax)
            else -> "nur Cheat-Daten (%.2f über dem Alarm)".format((cheatMin ?: alert) - alert)
        }
    }

    /**
     * German description of a scenario's expectation.
     *
     * @param scenario the scenario.
     * @return e.g. `keine Meldung` or `eins von movement.speed.a, movement.timer.a mit Σ ≥ 2.0`.
     */
    fun expectation(scenario: Scenario): String {
        val expect = scenario.expect
        if (expect.none) return "keine Meldung"
        val parts = mutableListOf<String>()
        if (expect.checks.size == 1) parts += "${expect.checks.single()} mit Σ ≥ %.1f".format(expect.minVl)
        if (expect.checks.size > 1) parts += "eins von ${expect.checks.joinToString()} mit Σ ≥ %.1f".format(expect.minVl)
        if (expect.all.isNotEmpty()) parts += "alle von ${expect.all.joinToString()} mit Σ ≥ %.1f".format(expect.minVl)
        when (expect.setback) {
            true -> parts += "mit Setback"
            false -> parts += "ohne Setback"
            null -> {}
        }
        return parts.joinToString("; ").ifBlank { "–" }
    }

    /**
     * Renders `report.json`.
     *
     * @param report the run.
     * @return pretty JSON.
     */
    fun json(report: RunReport): String = json.encodeToString(RunReport.serializer(), report)

    /**
     * One-line summary (log, chat, `local.sh`).
     *
     * @param report the run.
     * @return the line.
     */
    fun summary(report: RunReport): String =
        "suite=${report.info.suite} passed=${report.count(Status.PASS)} warned=${report.count(Status.WARN)} " +
            "failed=${report.count(Status.FAIL)} skipped=${report.count(Status.SKIP)} exempt=${report.exemptPasses} gaps=${report.count(Status.GAP)}" +
            (report.info.aborted?.let { " aborted=\"$it\"" } ?: "")

    /**
     * Renders `report.md` (German): header, result, failures, known gaps, `pass (exempt)` scenarios, the scenario
     * table, per-scenario details (expected vs. actual checks, max. VL, exemptions and tags with their
     * share of ticks, trace paths), check coverage and the threshold calibration.
     *
     * @param report the run.
     * @return Markdown.
     */
    fun markdown(report: RunReport): String = buildString {
        val info = report.info
        appendLine("# GuardLab-Bericht – Suite `${info.suite}`")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Client | ${info.player} (${info.clientVersion.ifBlank { "?" }}, ${info.clientBrand.ifBlank { "?" }}) |")
        appendLine("| Dummy-Client | ${if (info.dummy) "verbunden" else "nicht verbunden (Spieler-Ziel-Szenarien übersprungen)"} |")
        appendLine("| Service | ${info.service} |")
        appendLine("| Zeitraum | ${info.startedAt} – ${info.finishedAt} |")
        appendLine("| Messung | ${info.probe} |")
        appendLine("| Toxiproxy | ${info.toxiproxy} |")
        appendLine("| IGuard-Sanktionen | ${info.sanctions} |")
        info.aborted?.let { appendLine("| **Abgebrochen** | $it |") }
        appendLine()
        val exempt = report.exemptPasses
        appendLine(
            "**Ergebnis:** ${report.count(Status.PASS)} bestanden" +
                (if (exempt > 0) " (davon $exempt nur, weil IGuard kaum prüfte – `pass (exempt)`)" else "") +
                ", ${report.count(Status.WARN)} mit Warnung, ${report.count(Status.FAIL)} fehlgeschlagen, ${report.count(Status.SKIP)} übersprungen" +
                (if (report.count(Status.GAP) > 0) ", ${report.count(Status.GAP)} bekannte Lücke(n)" else ""),
        )
        appendLine()
        val failed = report.results.filter { it.status == Status.FAIL }
        if (failed.isNotEmpty()) {
            appendLine("## Fehlgeschlagen")
            appendLine()
            failed.forEach { r ->
                appendLine("- **${r.id}** (${r.category}, ${r.cheat}, Lag ${r.lag}) – ${r.title}")
                r.reasons.forEach { appendLine("  - $it") }
                r.trace?.let { appendLine("  - Trace: `$it`") }
                r.iguardTrace?.let { appendLine("  - IGuard-Trace: `$it`") }
            }
            appendLine()
        }
        val gaps = report.results.filter { it.status == Status.GAP }
        if (gaps.isNotEmpty()) {
            appendLine("## Bekannte Lücken")
            appendLine()
            appendLine("Szenarien mit `knownGap`: IGuard erkennt das (noch) nicht – keine Regression, aber offen.")
            appendLine()
            gaps.forEach { r ->
                appendLine("- **${r.id}** (${r.cheat}) – ${r.title}")
                r.reasons.filter { it != "bekannte Lücke" }.forEach { appendLine("  - $it") }
                r.iguardTrace?.let { appendLine("  - IGuard-Trace: `$it`") }
            }
            appendLine()
        }
        val unchecked = report.results.filter { it.status == Status.PASS && it.exempt }
        if (unchecked.isNotEmpty()) {
            appendLine("## Bestanden, aber kaum geprüft (`pass (exempt)`)")
            appendLine()
            appendLine("IGuard hat hier über ${Evaluator.percent(Evaluator.EXEMPT_SHARE)} der Ticks ausgesetzt – das Bestehen sagt nichts über Fehlalarme aus.")
            appendLine()
            unchecked.forEach { r -> appendLine("- **${r.id}** – ${r.title}: ${r.reasons.firstOrNull().orEmpty()}") }
            appendLine()
        }
        appendLine("## Alle Szenarien")
        appendLine()
        appendLine("| # | Szenario | Art | Cheat | Lag | Ergebnis | Meldungen | Setbacks | Ø Ping | Begründung |")
        appendLine("|---:|---|---|---|---|---|---|---:|---:|---|")
        report.results.forEach { r ->
            val flags = r.observation.checks.filter { it.flags > 0 }.joinToString("<br>") { "${it.check} %.2f".format(it.total) }.ifBlank { "–" }
            val ping = "%.0f".format(r.observation.avgPing)
            appendLine(
                "| ${r.index + 1} | `${r.id}` ${cell(r.title)} | ${r.category} | ${r.cheat} | ${r.lag} | ${badge(r)} | $flags | " +
                    "${r.observation.setbacks} | $ping | ${cell(r.reasons.joinToString("; "))} |",
            )
        }
        appendLine()
        appendLine("## Details je Szenario")
        appendLine()
        report.results.forEach { r -> details(r) }
        appendLine("## Abdeckung der Checks")
        appendLine()
        appendLine("| Check | Zustand | Legit-Szenarien | Cheat-Szenarien | erkannt in | gemeldet bei legit | Hinweis |")
        appendLine("|---|---|---:|---:|---|---|---|")
        report.coverage.forEach { c ->
            appendLine(
                "| `${c.check}` | ${c.state} | ${c.legit.size} | ${c.cheat.size} | ${c.detected.joinToString(", ").ifBlank { "–" }} | " +
                    "${c.flaggedLegit.joinToString(", ").ifBlank { "–" }} | ${cell(c.note)} |",
            )
        }
        appendLine()
        calibrationSection(report.calibration)
        appendLine(
            "Traces in `traces/`: `NNN_<szenario>.jsonl` von GuardLab (eine Zeile pro Server-Tick: Position, Geschwindigkeit, " +
                "onGround, Blick, Ping, TPS, VL-Anstiege, Marker) und `NNN_<szenario>.iguard.jsonl` von IGuard (eine Zeile pro " +
                "Frame: Eingaben, Umgebung, Exemptions, Ergebnis jedes Checks, Marker `guardlab:…`).",
        )
    }

    private fun StringBuilder.details(r: ScenarioResult) {
        appendLine("### ${r.index + 1}. `${r.id}` – ${r.title}")
        appendLine()
        appendLine("- **Ergebnis:** ${badge(r)} – ${r.reasons.joinToString("; ").ifBlank { "–" }}")
        if (r.status == Status.SKIP) {
            appendLine()
            return
        }
        val o = r.observation
        appendLine("- **Erwartet:** ${r.expectation.ifBlank { if (r.expected.isEmpty()) "keine Meldung" else r.expected.joinToString() }}")
        val actual = o.checks.filter { it.flags > 0 || it.failed > 0 }
        appendLine("- **Tatsächlich:** " + if (actual.isEmpty()) "keine Meldung" else actual.joinToString("; ") { observed(it, o.frames) })
        val top = o.checks.maxByOrNull { it.peak }
        appendLine("- **Max. VL:** " + if (top == null || top.peak <= 0.0) "0" else "%.2f (`${top.check}`)".format(top.peak))
        val frames = o.frames
        if (frames == null || frames.frames == 0L) {
            appendLine("- **IGuard-Prüfung:** keine Frame-Berichte – gemessen über VL-Anstiege pro Tick")
        } else {
            val movement = frames.movementExemptShare?.let { "${Evaluator.percent(it)} von ${frames.movementTicks} Bewegungs-Ticks" } ?: "keine Bewegungs-Frames"
            appendLine(
                "- **IGuard-Prüfung:** ${frames.frames} Frames, ${frames.ticks} geprüfte Ticks; Bewegungs-Checks ausgesetzt: $movement; " +
                    "alle Checks ausgesetzt: ${Evaluator.percent(frames.allExemptShare)}",
            )
            appendLine("- **Exemptions:** " + shares(frames.exemptions, "keine"))
            appendLine("- **Umgebung (Tags):** " + shares(frames.tags, "–"))
            if (r.covers.isNotEmpty()) {
                appendLine(
                    "- **Szenario-Checks:** " + r.covers.joinToString("; ") { check ->
                        val a = frames.activity(check)
                        if (a == null) "`$check` nie geprüft" else "`$check` ${a.evaluated}× geprüft, ${a.failed}× fehlgeschlagen, ${a.counted}× gezählt"
                    },
                )
            }
        }
        appendLine("- **Setbacks / Ping:** ${o.setbacks} / Ø %.0f ms (max. ${o.maxPing} ms)".format(o.avgPing))
        val traces = listOfNotNull(r.trace?.let { "GuardLab `$it`" }, r.iguardTrace?.let { "IGuard `$it`" })
        appendLine("- **Traces:** " + traces.joinToString(", ").ifBlank { "–" })
        appendLine()
    }

    private fun observed(c: CheckObservation, frames: FrameSummary?): String {
        val alert = frames?.activity(c.check)?.alertVl?.takeUnless(Double::isNaN)
        val counts = if (c.failed > 0) "${c.flags}× gezählt/${c.failed}× fehlgeschlagen" else "${c.flags}×"
        return "`${c.check}` $counts, Σ %.2f, Spitze %.2f".format(c.total, c.peak) + (alert?.let { " (Alarm %.1f)".format(it) } ?: "")
    }

    private fun shares(list: List<Share>, empty: String): String =
        if (list.isEmpty()) empty else list.take(MAX_SHARES).joinToString(", ") { "${it.name} ${Evaluator.percent(it.share)}" } +
            if (list.size > MAX_SHARES) " …" else ""

    private fun StringBuilder.calibrationSection(rows: List<CalibrationRow>) {
        appendLine("## Kalibrierung")
        appendLine()
        if (rows.isEmpty()) {
            appendLine("Keine Daten.")
            appendLine()
            return
        }
        appendLine(
            "Höchste VL (über dem Startwert) in legit-Szenarien gegen die niedrigste VL der Cheat-Szenarien, die den Check " +
                "erwarten – jeweils verglichen mit der Alarm-Schwelle (`alert-vl`). Gut kalibriert: legit bleibt darunter, jeder Cheat erreicht sie.",
        )
        appendLine()
        appendLine("| Check | Alarm-VL | max. VL legit | in | min. VL Cheat | in | Bewertung |")
        appendLine("|---|---:|---:|---|---:|---|---|")
        rows.forEach { c ->
            appendLine(
                "| `${c.check}` | %.1f | ${c.legitMax?.let { "%.2f".format(it) } ?: "–"} | ${c.legitScenario ?: "–"} | ".format(c.alertVl) +
                    "${c.cheatMin?.let { "%.2f".format(it) } ?: "–"} | ${c.cheatScenario ?: "–"} | ${cell(c.verdict)} |",
            )
        }
        appendLine()
    }

    private fun badge(result: ScenarioResult): String =
        if (result.status == Status.PASS && result.exempt) "✅ bestanden (exempt)" else badge(result.status)

    private fun badge(status: Status): String = when (status) {
        Status.PASS -> "✅ bestanden"
        Status.WARN -> "⚠️ Warnung"
        Status.FAIL -> "❌ Fehler"
        Status.SKIP -> "⏭ übersprungen"
        Status.GAP -> "🕳 bekannte Lücke"
    }

    private const val MAX_SHARES = 6

    private fun cell(text: String): String = text.replace("|", "\\|").replace("\n", " ")
}
