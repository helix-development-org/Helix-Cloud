package de.tytoss.iguard.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.io.path.readText
import kotlin.test.Test

/**
 * Offline re-judgement of a whole GuardLab run: every scenario's IGuard recording
 * (`traces/NNN_<id>.iguard.jsonl`) is replayed through the CURRENT check engine and judged like GuardLab does
 * (legit: no flag at alert level, sub-alert flags are a warning; cheat: an expected check sums ≥ 1.0). The
 * table compares that with the status of the recorded run, so a check change can be validated against every
 * scenario without playing them again.
 *
 * ```
 * IGUARD_REPLAY_RUN=<…/plugins/GuardLab/reports/<ts>-<suite>> ./gradlew :helix-addon-guard-paper:test --tests '*RunReplayTest*'
 * ```
 *
 * Writes `build/reports/iguard-replay.md` and prints it. Skipped without the variable.
 */
class RunReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    private data class Row(val id: String, val category: String, val recorded: String, val replayed: String, val detail: String)

    @Test
    fun `replay a GuardLab run on request`() {
        val run = System.getenv("IGUARD_REPLAY_RUN").orEmpty()
        Assumptions.assumeTrue(run.isNotBlank(), "set IGUARD_REPLAY_RUN=<GuardLab report directory> to replay a run")
        val dir = Paths.get(run)
        val report = json.parseToJsonElement(dir.resolve("report.json").readText()).jsonObject
        val rows = report["results"]!!.jsonArray.mapNotNull { element -> judge(dir, element.jsonObject) }
        val text = render(dir, rows)
        val out = Paths.get("build/reports/iguard-replay.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, text)
        println(text)
    }

    private fun judge(dir: Path, result: JsonObject): Row? {
        val id = result.text("id")
        val index = result["index"]!!.jsonPrimitive.int
        val trace = dir.resolve("traces/%03d_%s.iguard.jsonl".format(index + 1, id))
        val recorded = result.text("status")
        val category = result.text("category")
        if (!Files.exists(trace)) return Row(id, category, recorded, "–", "keine IGuard-Aufnahme")
        val replay = runCatching { TraceReplay().replay(trace) }.getOrElse { return Row(id, category, recorded, "ERROR", it.message.orEmpty()) }
        val counted = replay.reports.flatMap { it.results }.filter { it.counted }
        val totals = counted.groupBy { it.checkId }.mapValues { (_, results) -> results.sumOf { it.weight } }
        val alerts = replay.reports.flatMap { it.results }.associate { it.checkId to it.alertLevel }
        val peaks = totals.keys.associateWith(replay::peakViolation)
        val detail = totals.entries.sortedByDescending { it.value }.joinToString { (check, total) ->
            "$check Σ%.2f peak %.2f/%.0f".format(Locale.ROOT, total, peaks[check] ?: 0.0, alerts[check] ?: Double.NaN)
        }.ifEmpty { "–" }
        val expected = result["expected"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val replayed = when {
            recorded == "SKIP" -> "SKIP"
            category == "cheat" || expected.isNotEmpty() -> when {
                expected.any { (totals[it] ?: 0.0) >= MIN_VL } -> "PASS"
                recorded == "GAP" -> "GAP"
                else -> "FAIL"
            }
            peaks.any { (check, peak) -> peak >= (alerts[check] ?: Double.MAX_VALUE) } -> "FAIL"
            totals.isNotEmpty() -> "WARN"
            else -> "PASS"
        }
        return Row(id, category, recorded, replayed, detail)
    }

    private fun render(dir: Path, rows: List<Row>): String = buildString {
        val changed = rows.filter { it.recorded != it.replayed }
        appendLine("# IGuard-Replay von ${dir.fileName}")
        appendLine()
        appendLine("Aufgenommen → nachgespielt mit dem aktuellen Stand: ${rows.size} Szenarien, ${changed.size} geändert.")
        appendLine()
        listOf("PASS", "WARN", "FAIL", "GAP", "SKIP").forEach { status ->
            appendLine("- $status: ${rows.count { it.recorded == status }} → ${rows.count { it.replayed == status }}")
        }
        appendLine()
        appendLine("## Geändert")
        appendLine()
        table(changed)
        appendLine()
        appendLine("## Alle")
        appendLine()
        table(rows)
    }

    private fun StringBuilder.table(rows: List<Row>) {
        appendLine("| Szenario | Art | Lauf | Replay | Meldungen (Replay) |")
        appendLine("|---|---|---|---|---|")
        rows.forEach { appendLine("| `${it.id}` | ${it.category} | ${it.recorded} | ${it.replayed} | ${it.detail} |") }
    }

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content.orEmpty()

    private companion object {
        /** GuardLab's default `minVl` for cheat expectations. */
        const val MIN_VL = 1.0
    }
}
