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

    /** Checks without a cheat scenario, with the reason (they are still covered by legit scenarios). */
    val NOT_PROVOKED: Map<String, String> = mapOf(
        "client.identity.a" to "braucht einen gefälschten Client-Fingerabdruck (kein Cheat-Modus im Vertrag)",
        "client.brand_spoof.a" to "braucht eine gefälschte Client-Brand (kein Cheat-Modus im Vertrag)",
        "movement.phase.a" to "braucht einen Noclip-Modus (Vertragserweiterung `phase`)",
        "movement.sprintbackwards.a" to "braucht Omni-Sprint (Vertragserweiterung `omnisprint`)",
        "combat.inventory.a" to "braucht Angriffe bei offenem Inventar (Vertragserweiterung)",
        "inventory.impossible.a" to "braucht unmögliche Inventar-Klicks (Vertragserweiterung)",
        "inventory.move.a" to "braucht Inventar-Klicks während der Bewegung (Vertragserweiterung)",
    )
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
 * @property trace trace file relative to the report directory.
 * @property startedAt start (ISO-8601).
 * @property durationMs wall time.
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
 * A complete run.
 *
 * @property info header.
 * @property results scenario results in run order.
 * @property coverage per-check coverage.
 */
@Serializable
data class RunReport(val info: RunInfo, val results: List<ScenarioResult>, val coverage: List<CoverageRow>) {
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
        val expecting = ran.filter { scenarios[it.id]?.let { s -> check in s.expect.checks || check in s.expect.all } == true }
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
            "failed=${report.count(Status.FAIL)} skipped=${report.count(Status.SKIP)}" +
            (report.info.aborted?.let { " aborted=\"$it\"" } ?: "")

    /**
     * Renders `report.md` (German).
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
        appendLine(
            "**Ergebnis:** ${report.count(Status.PASS)} bestanden, ${report.count(Status.WARN)} mit Warnung, " +
                "${report.count(Status.FAIL)} fehlgeschlagen, ${report.count(Status.SKIP)} übersprungen",
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
            }
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
                "| ${r.index + 1} | `${r.id}` ${cell(r.title)} | ${r.category} | ${r.cheat} | ${r.lag} | ${badge(r.status)} | $flags | " +
                    "${r.observation.setbacks} | $ping | ${cell(r.reasons.joinToString("; "))} |",
            )
        }
        appendLine()
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
        appendLine("Traces (eine JSON-Zeile pro Tick: Position, Geschwindigkeit, onGround, Blick, Ping, TPS, VL-Anstiege) liegen in `traces/`.")
    }

    private fun badge(status: Status): String = when (status) {
        Status.PASS -> "✅ bestanden"
        Status.WARN -> "⚠️ Warnung"
        Status.FAIL -> "❌ Fehler"
        Status.SKIP -> "⏭ übersprungen"
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace("\n", " ")
}
