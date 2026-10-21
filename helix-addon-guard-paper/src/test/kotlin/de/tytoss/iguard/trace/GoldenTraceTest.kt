package de.tytoss.iguard.trace

import de.tytoss.iguard.config.IGuardConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden-trace regression tests: every `src/test/resources/traces/<name>.jsonl` is replayed through
 * the check engine and judged against `<name>.expect.json`:
 *
 * ```json
 * { "flags": ["movement.speed.a"], "clean": ["*"], "minCounted": {"movement.speed.a": 3} }
 * ```
 *
 * - `flags`: checks that must fail at least once;
 * - `clean`: checks that must never fail (`*` = every check not listed in `flags`);
 * - `minCounted`: optional minimum number of counted (VL-raising) failures per check.
 *
 * To turn a recording from the test server into a regression test, copy the `.jsonl` file from
 * `plugins/IGuard/traces/` here and add an expectation file next to it.
 */
class GoldenTraceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @TestFactory
    fun `golden traces replay to their expected verdicts`(): List<DynamicTest> {
        val directory = Paths.get(javaClass.getResource("/traces")?.toURI() ?: fail("no /traces test resources"))
        val traces = Files.list(directory).use { files -> files.filter { it.name.endsWith(".jsonl") }.sorted().toList() }
        assertTrue(traces.isNotEmpty(), "no golden traces found")
        return traces.map { trace -> DynamicTest.dynamicTest(trace.name) { verify(trace) } }
    }

    @Test
    fun `regenerate synthetic traces on request`() {
        Assumptions.assumeTrue(System.getenv("IGUARD_REGENERATE_TRACES") == "1", "set IGUARD_REGENERATE_TRACES=1 to regenerate")
        SyntheticTraces.writeAll(Paths.get("src/test/resources/traces"))
    }

    private fun verify(trace: Path) {
        val expectationFile = trace.resolveSibling(trace.name.removeSuffix(".jsonl") + ".expect.json")
        if (!Files.exists(expectationFile)) fail("missing ${expectationFile.name} for ${trace.name}")
        val expectation = json.parseToJsonElement(expectationFile.readText()).jsonObject
        val flags = expectation.strings("flags")
        val clean = expectation.strings("clean").let { listed ->
            if ("*" in listed) IGuardConfig.checkIds - flags + (listed - "*") else listed
        }
        val result = TraceReplay().replay(trace)
        val failed = result.failedChecks
        val missing = flags - failed
        val unexpected = failed.intersect(clean)
        val minimums = expectation["minCounted"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content.toInt() }
        val tooFew = minimums.filter { (check, minimum) -> result.countedFailures(check) < minimum }
        if (missing.isNotEmpty() || unexpected.isNotEmpty() || tooFew.isNotEmpty()) {
            fail(
                "${trace.name}: missing flags $missing, unexpected flags $unexpected, " +
                    "too few counted ${tooFew.keys.associateWith(result::countedFailures)}; failed=$failed peak=" +
                    failed.associateWith(result::peakViolation),
            )
        }
    }

    private fun JsonObject.strings(key: String): Set<String> =
        get(key)?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }.orEmpty()
}
